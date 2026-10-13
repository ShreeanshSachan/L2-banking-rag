package com.example.demo.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Masks sensitive financial identifiers before they leave the system.
 * Account / card numbers are reduced to their last 4 digits.
 */
@Component
public class MaskingUtil {

    public String maskAccountNumber(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String digits = value.replaceAll("\\s", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "****" + digits.substring(digits.length() - 4);
    }

    public String maskValueForKey(String key, Object value) {
        if (value == null) {
            return null;
        }
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String str = String.valueOf(value);
        if (k.contains("account_number") || k.contains("account_no") || k.contains("card_number")
                || k.contains("pan") || k.contains("card_no")) {
            return maskAccountNumber(str);
        }
        return str;
    }

    public Map<String, Object> maskRow(Map<String, Object> row) {
        Map<String, Object> masked = new LinkedHashMap<>();
        row.forEach((k, v) -> masked.put(k, maskValueForKey(k, v)));
        return masked;
    }
}
