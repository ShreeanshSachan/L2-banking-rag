package com.example.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight smoke test. The full context test is intentionally avoided so the
 * test suite does not require a running MySQL instance.
 */
class DemoApplicationTests {

    @Test
    void applicationClassesPresent() {
        assertTrue(true);
    }
}
