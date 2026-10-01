package com.fashion.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only storefront settings.
 *
 * The contact details shown in the footer are not hardcoded anywhere in the
 * frontend: they are seeded into the business_config table by Flyway and served
 * from here, so the page always reflects whatever the operator configured.
 */
@RestController
@RequestMapping("/api/v1/config")
@CrossOrigin(origins = "${app.cors-origin:http://localhost:3000}")
public class StorefrontConfigController {

    /**
     * The only keys the storefront is allowed to publish. Anything else added to
     * business_config stays server-side.
     */
    private static final List<String> PUBLIC_KEYS = List.of(
            "business_address",
            "business_hours",
            "contact_email",
            "contact_phone",
            "social_instagram",
            "social_pinterest"
    );

    private final JdbcTemplate db;

    public StorefrontConfigController(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/business")
    public Map<String, String> business() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : PUBLIC_KEYS) {
            try {
                String value = db.queryForObject(
                        "SELECT value FROM business_config WHERE key = ?",
                        String.class,
                        key
                );
                if (value != null && !value.isBlank()) {
                    result.put(key, value);
                }
            } catch (Exception missingOrUnreadable) {
                // A key that was never configured is simply absent from the response.
            }
        }
        return result;
    }
}
