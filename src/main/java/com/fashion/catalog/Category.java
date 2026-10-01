package com.fashion.catalog;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * Canonical taxonomy node: Gender -> Main Category -> Subcategory.
 * Slugs are unique per gender (roots) and per parent (children), enforced in V18.
 */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    private UUID id;

    @Column(nullable = false, length = 10)
    private String gender;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String slug;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt = java.time.Instant.now();

    protected Category() {
    }

    public UUID getId() {
        return id;
    }

    public String getGender() {
        return gender;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public UUID getParentId() {
        return parentId;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isRoot() {
        return parentId == null;
    }
}
