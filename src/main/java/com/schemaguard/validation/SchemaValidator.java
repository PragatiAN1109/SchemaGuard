package com.schemaguard.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SchemaValidator {

    private static final String PLAN_SCHEMA_PATH = "/schema/schema.json";

    private final ObjectMapper objectMapper;
    private final JsonSchema planSchema;

    public SchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.planSchema = loadSchema(PLAN_SCHEMA_PATH);
    }

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

    private JsonSchema loadSchema(String classpathLocation) {
        InputStream is = SchemaValidator.class.getResourceAsStream(classpathLocation);
        if (is == null) {
            throw new IllegalStateException("Schema not found on classpath: " + classpathLocation);
        }
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        return factory.getSchema(is);
    }
}