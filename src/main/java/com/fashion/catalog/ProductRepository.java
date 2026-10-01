
package com.fashion.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findAllByActiveTrue(Pageable pageable);

    Optional<Product> findBySlugAndActiveTrue(String slug);

    /**
     * open-in-view is disabled, so the recommendation lookup has to initialise
     * the lazy product_categories association inside the query itself.
     */
    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.categories WHERE p.id = :id")
    Optional<Product> findByIdWithCategories(@Param("id") UUID id);

    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.categories WHERE p.slug = :slug AND p.active = true")
    Optional<Product> findBySlugWithCategories(@Param("slug") String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    @Query(
        value = """
            SELECT p FROM Product p
            WHERE p.active = true
            AND (
                CAST(:q AS string) IS NULL
                OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(p.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(p.category) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            )
            AND (
                CAST(:category AS string) IS NULL
                OR LOWER(p.category) = LOWER(CAST(:category AS string))
            )
            AND (:minPrice IS NULL OR p.price >= :minPrice)
            AND (:maxPrice IS NULL OR p.price <= :maxPrice)
            """,
        countQuery = """
            SELECT COUNT(p) FROM Product p
            WHERE p.active = true
            AND (
                CAST(:q AS string) IS NULL
                OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(p.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(p.category) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            )
            AND (
                CAST(:category AS string) IS NULL
                OR LOWER(p.category) = LOWER(CAST(:category AS string))
            )
            AND (:minPrice IS NULL OR p.price >= :minPrice)
            AND (:maxPrice IS NULL OR p.price <= :maxPrice)
            """
    )
    Page<Product> search(
        @Param("q") String q,
        @Param("category") String category,
        @Param("minPrice") BigDecimal minPrice,
        @Param("maxPrice") BigDecimal maxPrice,
        Pageable pageable
    );

    /**
     * Single taxonomy-aware collection query. Every dimension is optional and
     * they combine, so a subcategory is never silently ignored when a gender or
     * main category is also supplied.
     */
    @Query(
        value = """
            SELECT DISTINCT p FROM Product p
            WHERE p.active = true
            AND (CAST(:gender AS string) IS NULL OR UPPER(p.gender) = UPPER(CAST(:gender AS string)))
            AND (CAST(:subcategory AS string) IS NULL OR LOWER(p.subcategory) = LOWER(CAST(:subcategory AS string)))
            AND (
                CAST(:mainCategory AS string) IS NULL
                OR EXISTS (
                    SELECT 1 FROM p.categories c
                    WHERE c.slug = CAST(:mainCategory AS string)
                      AND c.parentId IS NULL
                      AND (CAST(:gender AS string) IS NULL OR UPPER(c.gender) = UPPER(CAST(:gender AS string)))
                )
            )
            AND (
                CAST(:q AS string) IS NULL
                OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(p.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(p.category) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            )
            AND (:minPrice IS NULL OR p.price >= :minPrice)
            AND (:maxPrice IS NULL OR p.price <= :maxPrice)
            """,
        countQuery = """
            SELECT COUNT(DISTINCT p) FROM Product p
            WHERE p.active = true
            AND (CAST(:gender AS string) IS NULL OR UPPER(p.gender) = UPPER(CAST(:gender AS string)))
            AND (CAST(:subcategory AS string) IS NULL OR LOWER(p.subcategory) = LOWER(CAST(:subcategory AS string)))
            AND (
                CAST(:mainCategory AS string) IS NULL
                OR EXISTS (
                    SELECT 1 FROM p.categories c
                    WHERE c.slug = CAST(:mainCategory AS string)
                      AND c.parentId IS NULL
                      AND (CAST(:gender AS string) IS NULL OR UPPER(c.gender) = UPPER(CAST(:gender AS string)))
                )
            )
            AND (
                CAST(:q AS string) IS NULL
                OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(p.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR LOWER(p.category) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
            )
            AND (:minPrice IS NULL OR p.price >= :minPrice)
            AND (:maxPrice IS NULL OR p.price <= :maxPrice)
            """
    )
    Page<Product> browse(
        @Param("gender") String gender,
        @Param("mainCategory") String mainCategory,
        @Param("subcategory") String subcategory,
        @Param("q") String q,
        @Param("minPrice") BigDecimal minPrice,
        @Param("maxPrice") BigDecimal maxPrice,
        Pageable pageable
    );
}
