package com.schemaguard.validation;

import java.util.List;

public class SchemaValidationException extends RuntimeException {
    private final List<String> errors;

    public SchemaValidationException(String message, List<String> errors) {
        super(message);
        this.errors = errors;
    }

    public List<String> getErrors() {
        return errors;
    }
}