package com.fashion.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.UUID;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fashion.security.CustomerAuthController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/v1/admin/products")
@CrossOrigin(origins="${app.cors-origin:http://localhost:3000}")
public class ProductAdminController {
  private final ProductRepository repository; private final CustomerAuthController auth; private final JdbcTemplate db; private final ProductAssetStorage storage; private final ObjectMapper mapper;
  public ProductAdminController(ProductRepository repository, CustomerAuthController auth, JdbcTemplate db, ProductAssetStorage storage, ObjectMapper mapper) { this.repository = repository; this.auth=auth; this.db=db; this.storage=storage; this.mapper=mapper; }
  private void requireAdmin(String authorization) { if (authorization==null || !auth.isAdmin(authorization)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin role required"); }

  public record VariantInput(@NotBlank @Size(max=100) String sku,@NotBlank @Size(max=40) String size,
      @NotBlank @Size(max=80) String color,@Pattern(regexp="^#[0-9A-Fa-f]{6}$") String colorHex,
      @Min(0) int stockQuantity,String imageUrl) {}
  public record ProductRequest(@NotBlank @Size(max=180) String slug,
      @NotBlank @Size(max=240) String name, @Size(max=3000) String description,
      @NotBlank @Size(max=100) String category, @NotNull @DecimalMin("0.00") BigDecimal price,
      @DecimalMin("0.00") BigDecimal mrp, @Size(max=1000) String imageUrl, Boolean active,
      String productType,String garmentType,String brand,String collectionName,List<@Valid VariantInput> variants) {}

  public record ModelAssetRequest(UUID variantId, @NotBlank @Size(max=1200) String assetUrl,
      @Size(max=1200) String previewImageUrl, @NotBlank String status,
      @Size(max=240) String source, @Size(max=240) String licenseName,
      @Size(max=1200) String licenseReference, @Size(max=3000) String processingError) {}

  @PutMapping("/{id}/3d-assets")
  public Map<String,Object> saveModelAsset(@PathVariable UUID id,
      @RequestHeader(value="Authorization",required=false) String authorization,
      @Valid @RequestBody ModelAssetRequest request) {
    requireAdmin(authorization);
    if (!repository.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
    if (!Set.of("pending","processing","ready","failed").contains(request.status())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported asset status");
    try {
      URI asset = URI.create(request.assetUrl());
      String path = asset.getPath() == null ? "" : asset.getPath().toLowerCase();
      if (!"https".equalsIgnoreCase(asset.getScheme()) || !(path.endsWith(".glb") || path.endsWith(".gltf"))) throw new IllegalArgumentException();
      if (request.previewImageUrl()!=null && !"https".equalsIgnoreCase(URI.create(request.previewImageUrl()).getScheme())) throw new IllegalArgumentException();
    } catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a public HTTPS .glb or .gltf URL and an HTTPS preview URL"); }
    if (request.variantId()!=null && db.queryForObject("SELECT count(*) FROM product_variants WHERE id=? AND product_id=?",Integer.class,request.variantId(),id)==0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Variant does not belong to this product");
    if ("ready".equals(request.status()) && (request.licenseName()==null || request.licenseName().isBlank() || request.source()==null || request.source().isBlank()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ready assets require source and license metadata");
    UUID assetId=UUID.randomUUID();
    db.update("INSERT INTO product_3d_assets(id,product_id,variant_id,asset_url,preview_image_url,status,source,license_name,license_reference,processing_error) VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (product_id, (COALESCE(variant_id, '00000000-0000-0000-0000-000000000000'::uuid))) DO UPDATE SET asset_url=EXCLUDED.asset_url,preview_image_url=EXCLUDED.preview_image_url,status=EXCLUDED.status,source=EXCLUDED.source,license_name=EXCLUDED.license_name,license_reference=EXCLUDED.license_reference,processing_error=EXCLUDED.processing_error,updated_at=now()",
      assetId,id,request.variantId(),request.assetUrl(),request.previewImageUrl(),request.status(),request.source(),request.licenseName(),request.licenseReference(),request.processingError());
    return Map.of("status","saved","productId",id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Product create(@RequestHeader(value="Authorization",required=false) String authorization, @Valid @RequestBody ProductRequest request) {
    requireAdmin(authorization);
    if (repository.existsBySlug(request.slug())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Slug already exists");
    String type=request.productType()==null?"STANDARD":request.productType().toUpperCase();
    if(!Set.of("STANDARD","THREE_D").contains(type))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"productType must be STANDARD or THREE_D");
    UUID id=UUID.randomUUID();Product product=repository.save(new Product(id, request.slug(), request.name(), request.description(),
      request.category(), request.price(), request.mrp(), request.imageUrl(), request.active()==null || request.active(),type,request.garmentType(),request.brand(),request.collectionName()));
    List<VariantInput> variants=request.variants()==null?List.of():request.variants();
    if("THREE_D".equals(type)&&variants.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Add at least one size/color inventory variant");
    Set<String> keys=new java.util.HashSet<>();
    for(VariantInput v:variants){String key=v.size().trim().toLowerCase()+"|"+v.color().trim().toLowerCase();if(!keys.add(key))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Duplicate size/color variant: "+v.size()+" / "+v.color());
      try{db.update("INSERT INTO product_variants(id,product_id,sku,size,color,color_hex,stock_quantity,image_url,active) VALUES (?,?,?,?,?,?,?,?,true)",UUID.randomUUID(),id,v.sku().trim(),v.size().trim(),v.color().trim(),v.colorHex(),v.stockQuantity(),v.imageUrl());}
      catch(org.springframework.dao.DuplicateKeyException exception){throw new ResponseStatusException(HttpStatus.CONFLICT,"A SKU or size/color variant already exists");}}
    return product;
  }

  @PostMapping(value="/{id}/variants",consumes="application/json") @ResponseStatus(HttpStatus.CREATED) @Transactional
  public Map<String,Object> createVariant(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String authorization,@Valid @RequestBody VariantInput request){
    requireAdmin(authorization);if(!repository.existsById(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");UUID variantId=UUID.randomUUID();
    try{db.update("INSERT INTO product_variants(id,product_id,sku,size,color,color_hex,stock_quantity,image_url,active) VALUES (?,?,?,?,?,?,?,?,true)",variantId,id,request.sku().trim(),request.size().trim(),request.color().trim(),request.colorHex(),request.stockQuantity(),request.imageUrl());}
    catch(org.springframework.dao.DuplicateKeyException exception){throw new ResponseStatusException(HttpStatus.CONFLICT,"A SKU or size/color variant already exists");}
    return db.queryForMap("SELECT id,sku,size,color,color_hex AS \"colorHex\",stock_quantity AS \"stockQuantity\",image_url AS \"imageUrl\" FROM product_variants WHERE id=?",variantId);
  }

  @PostMapping(value="/{id}/3d-assets",consumes="multipart/form-data") @ResponseStatus(HttpStatus.CREATED) @Transactional
  public Map<String,Object> uploadModel(@PathVariable UUID id,@RequestHeader(value="Authorization",required=false) String authorization,
      @RequestPart("model") MultipartFile model,@RequestPart(value="thumbnail",required=false) MultipartFile thumbnail,
      @RequestParam(required=false) UUID variantId,@RequestParam(defaultValue="1") String modelVersion,
      @RequestParam(required=false) String availableMaterialNames,@RequestParam(required=false) String colorMaterialNames,@RequestParam(required=false) String source,
      @RequestParam(required=false) String licenseName,@RequestParam(required=false) String licenseReference){
    requireAdmin(authorization);if(!repository.existsById(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
    if(model==null||model.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a GLB model file");
    String original=model.getOriginalFilename()==null?"garment.glb":java.nio.file.Path.of(model.getOriginalFilename()).getFileName().toString();
    if(original.length()>255)original=original.substring(original.length()-255);
    if(!original.toLowerCase().endsWith(".glb")||model.getSize()>100L*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload a binary .glb file no larger than 100 MB");
    byte[] modelBytes;try{modelBytes=model.getBytes();validateGlb(modelBytes);}
    catch(java.io.IOException exception){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not read the uploaded GLB");}
    if(variantId!=null&&db.queryForObject("SELECT count(*) FROM product_variants WHERE id=? AND product_id=?",Integer.class,variantId,id)==0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Variant does not belong to this product");
    if(thumbnail!=null&&!thumbnail.isEmpty()&&(!Set.of("image/jpeg","image/png","image/webp").contains(thumbnail.getContentType())||thumbnail.getSize()>10L*1024*1024))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Thumbnail must be JPG, PNG, or WEBP and no larger than 10 MB");
    if(modelVersion.length()>80)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Model version is too long");
    if(source==null||source.isBlank()||source.length()>240||licenseName==null||licenseName.isBlank()||licenseName.length()>240)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Model source and license name are required");
    if(licenseReference!=null&&licenseReference.length()>1200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"License reference is too long");
    try{
      String assetUrl=storage.save(id,UUID.randomUUID()+".glb",modelBytes,"model/gltf-binary");String previewUrl=null;
      if(thumbnail!=null&&!thumbnail.isEmpty()){String ext=switch(thumbnail.getContentType()){case "image/jpeg"->"jpg";case "image/png"->"png";default->"webp";};previewUrl=storage.save(id,"preview-"+UUID.randomUUID()+"."+ext,thumbnail.getBytes(),thumbnail.getContentType());db.update("UPDATE products SET image_url=COALESCE(image_url,?) WHERE id=?",previewUrl,id);}
      List<String> availableSlots=availableMaterialNames==null?List.of():java.util.Arrays.stream(availableMaterialNames.split(",")).map(String::trim).filter(s->!s.isBlank()).distinct().limit(100).toList();
      List<String> slots=colorMaterialNames==null?List.of():java.util.Arrays.stream(colorMaterialNames.split(",")).map(String::trim).filter(s->!s.isBlank()).distinct().limit(100).toList();
      List<Map<String,String>> colors=db.query("SELECT DISTINCT color,color_hex FROM product_variants WHERE product_id=? AND active=true ORDER BY color",(rs,row)->Map.of("name",rs.getString("color"),"hex",rs.getString("color_hex")==null?"":rs.getString("color_hex")),id);
      Map<String,Object> config=new LinkedHashMap<>();config.put("availableMaterialNames",availableSlots);config.put("colorMaterialNames",slots);String configJson=mapper.writeValueAsString(config);String colorsJson=mapper.writeValueAsString(colors);
      List<UUID> existing=variantId==null?db.query("SELECT id FROM product_3d_assets WHERE product_id=? AND variant_id IS NULL",(rs,row)->rs.getObject("id",UUID.class),id):db.query("SELECT id FROM product_3d_assets WHERE product_id=? AND variant_id=?",(rs,row)->rs.getObject("id",UUID.class),id,variantId);
      UUID assetId=existing.isEmpty()?UUID.randomUUID():existing.get(0);
      if(existing.isEmpty())db.update("INSERT INTO product_3d_assets(id,product_id,variant_id,asset_url,preview_image_url,status,source,license_name,license_reference,original_filename,file_format,file_size_bytes,model_version,material_configuration,supported_colors) VALUES (?,?,?,?,?,'ready',?,?,?,?,?,?,?,?::jsonb,?::jsonb)",assetId,id,variantId,assetUrl,previewUrl,source,licenseName,licenseReference,original,"glb",model.getSize(),modelVersion,configJson,colorsJson);
      else db.update("UPDATE product_3d_assets SET asset_url=?,preview_image_url=?,status='ready',source=?,license_name=?,license_reference=?,original_filename=?,file_format='glb',file_size_bytes=?,model_version=?,material_configuration=?::jsonb,supported_colors=?::jsonb,updated_at=now() WHERE id=?",assetUrl,previewUrl,source,licenseName,licenseReference,original,model.getSize(),modelVersion,configJson,colorsJson,assetId);
      if(variantId!=null)db.update("UPDATE product_variants SET model_asset_id=? WHERE id=?",assetId,variantId);
      db.update("UPDATE products SET active=true,product_type='THREE_D' WHERE id=?",id);
      Map<String,Object> result=new LinkedHashMap<>();result.put("id",assetId);result.put("productId",id);result.put("assetUrl",assetUrl);result.put("previewImageUrl",previewUrl);result.put("originalFilename",original);result.put("fileFormat","glb");result.put("fileSizeBytes",model.getSize());result.put("status","ready");result.put("materialConfiguration",config);result.put("supportedColors",colors);return result;
    }catch(Exception exception){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not store the product model",exception);}
  }

  private void validateGlb(byte[] bytes){
    if(bytes.length<20||bytes[0]!='g'||bytes[1]!='l'||bytes[2]!='T'||bytes[3]!='F'||littleEndian(bytes,4)!=2||littleEndian(bytes,8)!=bytes.length)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"File content is not a valid GLB 2.0 model");
    int offset=12;byte[] json=null;while(offset+8<=bytes.length){int length=littleEndian(bytes,offset);int type=littleEndian(bytes,offset+4);offset+=8;if(length<0||offset+(long)length>bytes.length)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"GLB has an invalid chunk length");if(type==0x4E4F534A&&json==null)json=java.util.Arrays.copyOfRange(bytes,offset,offset+length);offset+=length;}
    if(offset!=bytes.length||json==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"GLB is missing its model description");
    try{var document=mapper.readTree(json);String version=document.path("asset").path("version").asText();if(!version.startsWith("2.")||!document.path("meshes").isArray()||document.path("meshes").isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"GLB must contain a glTF 2.0 scene with garment mesh data");}
    catch(java.io.IOException exception){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"GLB model description is invalid");}
  }
  private int littleEndian(byte[] bytes,int at){return (bytes[at]&255)|((bytes[at+1]&255)<<8)|((bytes[at+2]&255)<<16)|((bytes[at+3]&255)<<24);}

  @PutMapping("/{id}")
  public Product update(@PathVariable UUID id, @RequestHeader(value="Authorization",required=false) String authorization, @Valid @RequestBody ProductRequest request) {
    requireAdmin(authorization);
    Product old = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found"));
    if (repository.existsBySlugAndIdNot(request.slug(),id)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Slug already exists");
    old.update(request.slug(),request.name(),request.description(),request.category(),request.price(),request.mrp(),request.imageUrl(),request.active()==null || request.active());
    return repository.save(old);
  }
}
