package com.example.demo.service;

/**
 * Raised when the Gemini API is not configured, unreachable, or returns an error.
 * Callers treat this as "degrade gracefully" rather than a hard failure.
 */
public class GeminiUnavailableException extends RuntimeException {

    public GeminiUnavailableException(String message) {
        super(message);
    }

    public GeminiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
