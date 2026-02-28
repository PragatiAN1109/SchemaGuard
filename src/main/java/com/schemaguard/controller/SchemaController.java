package com.schemaguard.controller;

import com.schemaguard.validation.SchemaValidator;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the Plan JSON Schema for inspection and demo purposes.
 * The schema served here is the exact same file used to validate
 * POST, PUT, and PATCH requests — loaded once from the classpath at startup.
 */
@RestController
@RequestMapping("/api/v1/schema")
public class SchemaController {

    private final SchemaValidator schemaValidator;

    public SchemaController(SchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    /**
     * GET /api/v1/schema/plan
     * Returns the full Plan JSON Schema (JSON Schema draft 2020-12).
     */
    @GetMapping(value = "/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPlanSchema() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(schemaValidator.getSchemaAsString());
    }
}
