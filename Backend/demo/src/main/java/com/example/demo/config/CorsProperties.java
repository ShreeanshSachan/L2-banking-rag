package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "security")
public record CorsProperties(
        List<String> corsAllowedOrigins
) {
    public List<String> originsOrDefault() {
        return (corsAllowedOrigins == null || corsAllowedOrigins.isEmpty())
                ? List.of("http://localhost:4200", "http://localhost")
                : corsAllowedOrigins;
    }
}
