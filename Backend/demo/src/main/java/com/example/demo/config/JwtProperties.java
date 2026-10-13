package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        long expiration
) {
    public long expirationOrDefault() {
        return expiration > 0 ? expiration : 86_400_000L;
    }
}
