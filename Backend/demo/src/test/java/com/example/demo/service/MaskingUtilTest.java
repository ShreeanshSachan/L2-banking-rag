package com.example.demo.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaskingUtilTest {

    private final MaskingUtil masking = new MaskingUtil();

    @Test
    void masksAccountNumberKeepingLast4() {
        assertEquals("****8901", masking.maskAccountNumber("5010020045678901"));
    }

    @Test
    void masksShortAccountNumberEntirely() {
        assertEquals("****", masking.maskAccountNumber("12"));
    }

    @Test
    void masksRowKeys() {
        Map<String, Object> row = masking.maskRow(Map.of(
                "account_number", "5010020045678901",
                "balance", "125430.75"));
        assertEquals("****8901", row.get("account_number"));
        assertEquals("125430.75", row.get("balance"));
    }
}
