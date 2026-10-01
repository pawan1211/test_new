package com.fashion.catalog;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
@RestController @RequestMapping("/api/v1") @CrossOrigin(origins="${app.cors-origin:http://localhost:3000}")
public class ProductController {
  private final ProductRepository repo; private final JdbcTemplate db; private final TaxonomySearchService searchService;
  public ProductController(ProductRepository repo,JdbcTemplate db,TaxonomySearchService searchService){this.repo=repo;this.db=db;this.searchService=searchService;}
  public record VariantResponse(UUID id,String size,String color,String colorHex,int stockQuantity,String imageUrl,String sku,UUID modelAssetId){}
  public record ModelAssetResponse(UUID id,UUID productId,UUID variantId,String assetUrl,String previewImageUrl,String status,String source,String licenseName,String licenseReference,String originalFilename,String fileFormat,Long fileSizeBytes,String modelVersion,String materialConfiguration,String supportedColors){}
  @GetMapping("/products") public Page<Product> list(@RequestParam(required=false) String q,@RequestParam(required=false) String category,@RequestParam(required=false) @DecimalMin("0.0") BigDecimal minPrice,@RequestParam(required=false) @DecimalMin("0.0") BigDecimal maxPrice,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="12") @Min(1) @Max(48) int size,@RequestParam(defaultValue="newest") String sort,@RequestParam(required=false) String gender,@RequestParam(required=false) String subcategory,@RequestParam(required=false) String collection){
  Sort s=switch(sort){case "price-asc"->Sort.by("price").ascending();case "price-desc"->Sort.by("price").descending();case "name"->Sort.by("name").ascending();case "popularity"->Sort.by("popularityScore").descending().and(Sort.by("createdAt").descending());default->Sort.by("createdAt").descending();};
  String g=blank(gender)==null?null:blank(gender).toUpperCase(Locale.ROOT);
  if(g!=null&&!g.equals("MEN")&&!g.equals("WOMEN"))g=null;
  String sub=blank(subcategory);
  String main=blank(collection)!=null?blank(collection):blank(category);
  // Taxonomy navigation: gender + main category + subcategory combine, never shadow one another.
  if(g!=null||sub!=null||main!=null){
    Page<Product> byTaxonomy=repo.browse(g,main,sub,blank(q),minPrice,maxPrice,PageRequest.of(page,size,s));
    byTaxonomy.getContent().forEach(this::attachVariants);return byTaxonomy;
  }
  Page<Product> result=repo.search(blank(q),blank(category),minPrice,maxPrice,PageRequest.of(page,size,s));
  result.getContent().forEach(this::attachVariants);return result;
  }
  @GetMapping("/products/{slug}") public Product bySlug(@PathVariable String slug){Product p;try{p=repo.findById(UUID.fromString(slug)).filter(Product::isActive).orElseThrow(()->new NoSuchElementException("Product not found"));}catch(IllegalArgumentException exception){p=repo.findBySlugAndActiveTrue(slug).orElseThrow(()->new NoSuchElementException("Product not found"));}attachVariants(p);return p;}
  @GetMapping("/products/id/{id}") public Product byId(@PathVariable UUID id){Product p=repo.findById(id).filter(Product::isActive).orElseThrow(()->new NoSuchElementException("Product not found"));attachVariants(p);return p;}
  /**
   * Catalog-backed recommendations. Candidates are drawn from the live catalog
   * only, ranked by how closely they match the product being viewed:
   *   1. same gender + same subcategory (closest),
   *   2. same gender + one of the product's own main collections,
   *   3. anything else in the same gender, so the grid is never empty.
   * The source product is always excluded and results are de-duplicated by id,
   * so the same piece can never appear twice.
   */
  @GetMapping("/products/{slug}/related") public List<Product> related(@PathVariable String slug,@RequestParam(defaultValue="4") @Min(1) @Max(12) int limit){
  Product source=repo.findBySlugWithCategories(slug).orElseThrow(()->new NoSuchElementException("Product not found"));
  Sort s=Sort.by(Sort.Direction.DESC,"popularityScore").and(Sort.by(Sort.Direction.DESC,"createdAt"));
  List<Product> picked=new java.util.LinkedList<>();
  gather(picked,repo.browse(source.getGender(),null,source.getSubcategory(),null,null,null,PageRequest.of(0,limit+1,s)),source.getId(),limit);
  for(Category c:source.getCategories())
    if(c.getParentId()==null)
      gather(picked,repo.browse(source.getGender(),c.getSlug(),null,null,null,null,PageRequest.of(0,limit+1,s)),source.getId(),limit);
  gather(picked,repo.browse(source.getGender(),null,null,null,null,null,PageRequest.of(0,limit+1,s)),source.getId(),limit);
  List<Product> result=picked.stream().limit(limit).toList();
  result.forEach(this::attachVariants);
  return result;
  }
  private void gather(List<Product> picked,Page<Product> page,UUID exclude,int limit){
  if(picked.size()>=limit)return;
  for(Product candidate:page.getContent()){
    if(candidate.getId().equals(exclude))continue;
    if(picked.stream().anyMatch(x->x.getId().equals(candidate.getId())))continue;
    picked.add(candidate);
    if(picked.size()>=limit)return;
  }
  }
  @GetMapping("/products/id/{id}/3d-assets") public List<ModelAssetResponse> modelAssets(@PathVariable UUID id){if(!repo.existsById(id))throw new NoSuchElementException("Product not found");return getModelAssets(id);}
  @GetMapping("/products/{productKey}/3d-assets") public List<ModelAssetResponse> modelAssetsByKey(@PathVariable String productKey){UUID id;try{id=UUID.fromString(productKey);}catch(IllegalArgumentException exception){id=repo.findBySlugAndActiveTrue(productKey).orElseThrow(()->new NoSuchElementException("Product not found")).getId();}if(!repo.existsById(id))throw new NoSuchElementException("Product not found");return getModelAssets(id);}
  @GetMapping("/categories") public List<String> categories(){return repo.findAll().stream().filter(Product::isActive).map(Product::getCategory).distinct().sorted().toList();}

  /**
   * Taxonomy-aware search. Understands free-text queries such as
   * "men wide leg denim", "women cropped denim jacket" or
   * "women satin midi dress" by resolving the wording against the canonical
   * category / subcategory vocabulary first.
   */
  @GetMapping("/search") public Page<Product> search(@RequestParam String q,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="12") @Min(1) @Max(48) int size,@RequestParam(defaultValue="newest") String sort,@RequestParam(required=false) @DecimalMin("0.0") BigDecimal minPrice,@RequestParam(required=false) @DecimalMin("0.0") BigDecimal maxPrice){
  Sort s=switch(sort){case "price-asc"->Sort.by("price").ascending();case "price-desc"->Sort.by("price").descending();case "name"->Sort.by("name").ascending();case "popularity"->Sort.by("popularityScore").descending().and(Sort.by("createdAt").descending());default->Sort.by("createdAt").descending();};
  TaxonomySearchService.Resolved r=searchService.resolve(q);
  Page<Product> result=null;
  // Try each plausible reading of the query, most specific first, and keep the
  // first that actually has products. "denim jacket" can mean the Jackets
  // subcategory under Denim or the Jackets & Coats collection; the shopper
  // should see whichever one is real rather than an empty grid.
  for(TaxonomySearchService.Combination c:r.combinations()){
    result=repo.browse(c.gender(),c.mainCategory(),c.subcategory(),null,minPrice,maxPrice,PageRequest.of(page,size,s));
    if(result.getTotalElements()>0)break;
  }
  if(result==null){
    // The query named no collection, so browse() would be an unfiltered listing.
    // Match the words instead rather than returning the whole catalog.
    result=repo.search(q,null,minPrice,maxPrice,PageRequest.of(page,size,s));
  }else if(result.getTotalElements()==0&&!r.tokens().isEmpty()){
    // Nothing satisfied the taxonomy alone: fall back to matching the words that
    // were left over so a shopper is never shown an empty grid.
    TaxonomySearchService.Combination c=r.best();
    result=repo.browse(c.gender(),c.mainCategory(),c.subcategory(),String.join(" ",r.tokens()),minPrice,maxPrice,PageRequest.of(page,size,s));
  }
  result.getContent().forEach(this::attachVariants);return result;
  }
  private void attachVariants(Product p){
  List<VariantResponse> variants=db.query("SELECT id,size,color,color_hex,stock_quantity,image_url,sku,model_asset_id FROM product_variants WHERE product_id=? AND active=true ORDER BY color,size",
  (rs,row)->new VariantResponse(rs.getObject("id",UUID.class),rs.getString("size"),rs.getString("color"),rs.getString("color_hex"),rs.getInt("stock_quantity"),rs.getString("image_url"),rs.getString("sku"),rs.getObject("model_asset_id",UUID.class)),p.getId());
  p.setVariants(variants);
  p.setModelAssets(getModelAssets(p.getId()));
  }
  private List<ModelAssetResponse> getModelAssets(UUID productId){return db.query("SELECT id,product_id,variant_id,asset_url,preview_image_url,status,source,license_name,license_reference,original_filename,file_format,file_size_bytes,model_version,material_configuration::text AS material_configuration,supported_colors::text AS supported_colors FROM product_3d_assets WHERE product_id=? AND status='ready' ORDER BY variant_id NULLS FIRST",
  (rs,row)->new ModelAssetResponse(rs.getObject("id",UUID.class),rs.getObject("product_id",UUID.class),rs.getObject("variant_id",UUID.class),rs.getString("asset_url"),rs.getString("preview_image_url"),rs.getString("status"),rs.getString("source"),rs.getString("license_name"),rs.getString("license_reference"),rs.getString("original_filename"),rs.getString("file_format"),rs.getObject("file_size_bytes",Long.class),rs.getString("model_version"),rs.getString("material_configuration"),rs.getString("supported_colors")),productId);}
  private String blank(String s){return s==null||s.isBlank()?null:s.trim();}
}
