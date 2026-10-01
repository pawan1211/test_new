package com.fashion.catalog;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
@Entity @Table(name="products")
public class Product {
 @Id private UUID id;
 @Column(nullable=false,unique=true) private String slug;
 @Column(nullable=false) private String name;
 @Column(length=3000) private String description;
 @Column(nullable=false) private String category;
 @Column(nullable=false,precision=12,scale=2) private BigDecimal price;
 @Column(name="mrp",precision=12,scale=2) private BigDecimal mrp;
 @Column(name="image_url",length=1000) private String imageUrl;
 @Column(name="product_type",nullable=false,length=16) private String productType="STANDARD";
 @Column(name="garment_type",length=40) private String garmentType;
 @Column(length=120) private String brand;
 @Column(name="collection_name",length=160) private String collectionName;
 @Column(length=10) private String gender;
 @Column(length=100) private String subcategory;
 /** Units actually sold, summed from order_items. Drives the "Popularity" sort. */
 @Column(name="popularity_score",nullable=false) private int popularityScore;
 @ManyToMany(fetch=FetchType.LAZY)
 @JoinTable(name="product_categories",joinColumns=@JoinColumn(name="product_id"),inverseJoinColumns=@JoinColumn(name="category_id"))
 @com.fasterxml.jackson.annotation.JsonIgnore private java.util.Set<Category> categories=new java.util.LinkedHashSet<>();
 @Column(nullable=false) private boolean active=true;
 @Column(name="created_at",nullable=false) private java.time.Instant createdAt=java.time.Instant.now();
 @Transient private List<ProductController.VariantResponse> variants=List.of();
 @Transient private List<ProductController.ModelAssetResponse> modelAssets=List.of();
 public List<ProductController.VariantResponse> getVariants(){return variants;}
 public void setVariants(List<ProductController.VariantResponse> variants){this.variants=variants==null?List.of():variants;}
 public List<ProductController.ModelAssetResponse> getModelAssets(){return modelAssets;}
 public void setModelAssets(List<ProductController.ModelAssetResponse> modelAssets){this.modelAssets=modelAssets==null?List.of():modelAssets;}
 protected Product(){}
 public Product(UUID id,String slug,String name,String description,String category,BigDecimal price,BigDecimal mrp,String imageUrl,boolean active){this(id,slug,name,description,category,price,mrp,imageUrl,active,"STANDARD",null,null,null);}
 public Product(UUID id,String slug,String name,String description,String category,BigDecimal price,BigDecimal mrp,String imageUrl,boolean active,String productType,String garmentType,String brand,String collectionName){this.id=id;this.slug=slug;this.name=name;this.description=description;this.category=category;this.price=price;this.mrp=mrp;this.imageUrl=imageUrl;this.active=active;this.productType=productType;this.garmentType=garmentType;this.brand=brand;this.collectionName=collectionName;}
 public void update(String slug,String name,String description,String category,BigDecimal price,BigDecimal mrp,String imageUrl,boolean active){this.slug=slug;this.name=name;this.description=description;this.category=category;this.price=price;this.mrp=mrp;this.imageUrl=imageUrl;this.active=active;}
 public UUID getId(){return id;} public String getSlug(){return slug;} public String getName(){return name;} public String getDescription(){return description;} public String getCategory(){return category;} public BigDecimal getPrice(){return price;} public BigDecimal getMrp(){return mrp;} public String getImageUrl(){return imageUrl;} public boolean isActive(){return active;} public java.time.Instant getCreatedAt(){return createdAt;}
 public String getProductType(){return productType;} public String getGarmentType(){return garmentType;} public String getBrand(){return brand;} public String getCollectionName(){return collectionName;}
 public String getGender(){return gender;} public String getSubcategory(){return subcategory;} public int getPopularityScore(){return popularityScore;}
 public void assignTaxonomy(String gender,String subcategory){this.gender=gender;this.subcategory=subcategory;}
 @com.fasterxml.jackson.annotation.JsonIgnore public java.util.Set<Category> getCategories(){return categories;}
}
