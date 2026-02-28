package com.schemaguard.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SchemaValidator {

    // Canonical schema location — loaded once at startup from the classpath
    static final String PLAN_SCHEMA_CLASSPATH = "/schemas/plan-schema.json";

    private final ObjectMapper objectMapper;
    private final JsonSchema planSchema;

    // Raw schema string cached at startup — served by GET /api/v1/schema/plan
    private final String planSchemaRaw;

    public SchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.planSchemaRaw = readSchemaString(PLAN_SCHEMA_CLASSPATH);
        this.planSchema = parseSchema(planSchemaRaw);
    }

    /**
     * Validates a raw JSON string against the plan schema.
     * Throws SchemaValidationException (→ 400) if validation fails.
     * Used by POST, PUT, and PATCH (post-merge) in PlanController.
     */
    public void validatePlanJson(String rawJson) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            Set<ValidationMessage> errors = planSchema.validate(node);

            if (!errors.isEmpty()) {
                List<String> messages = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .sorted()
                        .collect(Collectors.toList());
                throw new SchemaValidationException("JSON Schema validation failed", messages);
            }
        } catch (SchemaValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new SchemaValidationException("Invalid JSON payload (parse error)", List.of(e.getMessage()));
        }
    }

    /**
     * Returns the raw JSON Schema string as loaded from the classpath.
     * Used by SchemaController to serve GET /api/v1/schema/plan.
     */
    public String getSchemaAsString() {
        return planSchemaRaw;
    }

    // --- private helpers ---

    private String readSchemaString(String classpathLocation) {
        try (InputStream is = SchemaValidator.class.getResourceAsStream(classpathLocation)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Plan schema not found on classpath: " + classpathLocation);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to read plan schema from classpath: " + classpathLocation, e);
        }
    }

    private JsonSchema parseSchema(String schemaJson) {
        try {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(schemaJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse plan JSON Schema", e);
        }
    }
}
