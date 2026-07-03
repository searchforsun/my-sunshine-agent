package com.sunshine.rag.admin.config;

public class ConfigVersionConflictException extends RuntimeException {
    public ConfigVersionConflictException(String message) {
        super(message);
    }
}
