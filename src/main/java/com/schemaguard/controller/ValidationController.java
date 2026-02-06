package com.schemaguard.controller;

import com.schemaguard.validation.SchemaValidationException;
import com.schemaguard.validation.SchemaValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/validate")
public class ValidationController {

    private final SchemaValidator schemaValidator = new SchemaValidator();

    @PostMapping("/plan")
    public ResponseEntity<?> validatePlan(@RequestBody String rawJson) {
        schemaValidator.validatePlanJson(rawJson);

        Map<String, Object> response = new HashMap<>();
        response.put("valid", true);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(SchemaValidationException.class)
    public ResponseEntity<?> handleSchemaError(SchemaValidationException e) {
        Map<String, Object> response = new HashMap<>();
        response.put("valid", false);
        response.put("message", e.getMessage());
        response.put("errors", e.getErrors());
        return ResponseEntity.badRequest().body(response);
    }
}