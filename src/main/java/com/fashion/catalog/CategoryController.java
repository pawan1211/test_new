package com.fashion.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Navigation taxonomy API. "Shop All" is intentionally NOT stored as a row:
 * it is a virtual collection (all products for a gender) rendered by the UI,
 * so it can never be duplicated inside the drawer.
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "${app.cors-origin:http://localhost:3000}")
public class CategoryController {

    private final JdbcTemplate db;

    public CategoryController(JdbcTemplate db) {
        this.db = db;
    }

    /** Full navigation tree for both genders: { "MEN": [...], "WOMEN": [...] }. */
    @GetMapping("/categories/tree")
    public Map<String, List<Map<String, Object>>> categoryTree() {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT c.id, c.gender, c.name, c.slug, c.parent_id, c.sort_order, " +
                "  (SELECT COUNT(*) FROM product_categories pc " +
                "     JOIN products p ON p.id = pc.product_id AND p.active = true " +
                "    WHERE pc.category_id = c.id) AS product_count " +
                "FROM categories c ORDER BY c.gender, c.sort_order");

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("MEN", buildTree(rows, "MEN"));
        result.put("WOMEN", buildTree(rows, "WOMEN"));
        return result;
    }

    /** Main categories for one gender. */
    @GetMapping("/categories/{gender}")
    public List<Map<String, Object>> categoriesByGender(@PathVariable String gender) {
        String g = normaliseGender(gender);
        return db.queryForList(
                "SELECT c.id, c.name, c.slug, c.sort_order, " +
                "  (SELECT COUNT(*) FROM product_categories pc " +
                "     JOIN products p ON p.id = pc.product_id AND p.active = true " +
                "    WHERE pc.category_id = c.id) AS product_count " +
                "FROM categories c WHERE c.gender = ? AND c.parent_id IS NULL ORDER BY c.sort_order", g);
    }

    /** Subcategories of one main category, with live counts. */
    @GetMapping("/categories/{gender}/{categorySlug}/subcategories")
    public List<Map<String, Object>> subcategories(@PathVariable String gender, @PathVariable String categorySlug) {
        String g = normaliseGender(gender);
        // The count is scoped to products that actually belong to THIS main
        // category, not just to the bare subcategory slug. "midi" exists under
        // both Dresses and Skirts, so a slug-only count would conflate them.
        return db.queryForList(
                "SELECT c.id, c.name, c.slug, c.sort_order, " +
                "  (SELECT COUNT(*) FROM product_categories pc " +
                "     JOIN products p ON p.id = pc.product_id AND p.active = true " +
                "    WHERE pc.category_id = parent.id AND p.subcategory = c.slug) AS product_count " +
                "FROM categories c " +
                "JOIN categories parent ON c.parent_id = parent.id " +
                "WHERE parent.gender = ? AND parent.slug = ? AND parent.parent_id IS NULL " +
                "ORDER BY c.sort_order", g, categorySlug);
    }

    private String normaliseGender(String gender) {
        if (gender == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gender is required");
        }
        String g = gender.trim().toUpperCase(Locale.ROOT);
        if (!g.equals("MEN") && !g.equals("WOMEN")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gender must be MEN or WOMEN");
        }
        return g;
    }

    private List<Map<String, Object>> buildTree(List<Map<String, Object>> rows, String gender) {
        Map<UUID, List<Map<String, Object>>> children = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (!gender.equals(row.get("gender"))) {
                continue;
            }
            UUID parentId = (UUID) row.get("parent_id");
            if (parentId != null) {
                children.computeIfAbsent(parentId, k -> new ArrayList<>()).add(row);
            }
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!gender.equals(row.get("gender")) || row.get("parent_id") != null) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>(row);
            node.put("children", children.getOrDefault(row.get("id"), List.of()));
            roots.add(node);
        }
        return roots;
    }
}
