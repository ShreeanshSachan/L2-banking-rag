package com.example.demo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard();

    @Test
    void acceptsScopedSelectAndAppendsLimit() {
        String sql = guard.sanitize("SELECT account_number, balance FROM accounts WHERE user_id = 1", 1L);
        assertTrue(sql.toLowerCase().contains("limit 100"));
    }

    @Test
    void keepsExistingLimitUnder100() {
        String sql = guard.sanitize("SELECT balance FROM accounts WHERE user_id = 7 LIMIT 5", 7L);
        assertTrue(sql.toLowerCase().contains("limit 5"));
    }

    @Test
    void capsLimitAbove100() {
        String sql = guard.sanitize("SELECT balance FROM accounts WHERE user_id = 7 LIMIT 500", 7L);
        assertTrue(sql.toLowerCase().contains("limit 100"));
    }

    @Test
    void allowsUpdatedAtColumn() {
        String sql = guard.sanitize(
                "SELECT outstanding, updated_at FROM cards WHERE user_id = 2", 2L);
        assertEquals(true, sql.toLowerCase().contains("updated_at"));
    }

    @Test
    void rejectsNonSelect() {
        assertThrows(SqlGuardException.class,
                () -> guard.sanitize("DELETE FROM accounts WHERE user_id = 1", 1L));
    }

    @Test
    void rejectsTableNotAllowed() {
        assertThrows(SqlGuardException.class,
                () -> guard.sanitize("SELECT * FROM secrets WHERE user_id = 1", 1L));
    }

    @Test
    void rejectsUnscopedQuery() {
        assertThrows(SqlGuardException.class,
                () -> guard.sanitize("SELECT * FROM accounts", 1L));
    }

    @Test
    void rejectsWrongUserScope() {
        assertThrows(SqlGuardException.class,
                () -> guard.sanitize("SELECT * FROM accounts WHERE user_id = 2", 1L));
    }

    @Test
    void stripsComments() {
        String sql = guard.sanitize("SELECT balance FROM accounts /* hi */ WHERE user_id = 1 -- trailing", 1L);
        assertTrue(!sql.contains("/*") && !sql.contains("--"));
    }
}
