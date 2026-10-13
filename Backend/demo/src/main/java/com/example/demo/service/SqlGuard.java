package com.example.demo.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces SQL safety for the SQL-RAG (text-to-SQL) path:
 *  - SELECT-only, single statement
 *  - comments and statement terminators stripped
 *  - table allow-list
 *  - mandatory user scoping (user_id = :currentUser)
 *  - mandatory LIMIT (capped at 100)
 */
@Component
public class SqlGuard {

    private static final Set<String> ALLOWED_TABLES = Set.of("users", "accounts", "cards", "loans");

    /** Blocked as whole words (so created_at / updated_at are not false positives). */
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "insert", "update", "delete", "drop", "alter", "create", "truncate", "replace",
            "grant", "revoke", "merge", "call", "exec", "execute", "password", "union", "outfile");

    /** Blocked as substrings. */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "into outfile", "dumpfile", "information_schema", "performance_schema",
            "sleep(", "benchmark(", "load_file(");

    private static final Pattern FROM_JOIN = Pattern.compile("\\b(?:from|join)\\s+([a-zA-Z_][a-zA-Z0-9_]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT = Pattern.compile("\\blimit\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    public String sanitize(String rawSql, Long userId) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new SqlGuardException("Empty SQL statement");
        }
        String sql = stripFences(rawSql);
        sql = stripComments(sql);
        sql = sql.replaceAll("\\s+", " ").trim();
        String sqlNoTrailingTerminator = sql.replaceAll(";+$", "").trim();

        String lower = sqlNoTrailingTerminator.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select")) {
            throw new SqlGuardException("Only SELECT statements are permitted");
        }
        if (lower.contains(";")) {
            throw new SqlGuardException("Multiple statements are not permitted");
        }
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b").matcher(lower).find()) {
                throw new SqlGuardException("Statement contains forbidden keyword: " + keyword);
            }
        }
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            if (lower.contains(fragment)) {
                throw new SqlGuardException("Statement contains forbidden token: " + fragment);
            }
        }

        Matcher matcher = FROM_JOIN.matcher(lower);
        boolean foundTable = false;
        while (matcher.find()) {
            foundTable = true;
            String table = matcher.group(1);
            if (!ALLOWED_TABLES.contains(table)) {
                throw new SqlGuardException("Table not in allow-list: " + table);
            }
        }
        if (!foundTable) {
            throw new SqlGuardException("Statement does not reference an allowed table");
        }

        String scopePattern = "user_id\\s*=\\s*" + userId;
        if (!Pattern.compile(scopePattern, Pattern.CASE_INSENSITIVE).matcher(sqlNoTrailingTerminator).find()) {
            throw new SqlGuardException("Statement is not scoped to the current user");
        }

        String result = sqlNoTrailingTerminator;
        Matcher limitMatcher = LIMIT.matcher(result);
        if (limitMatcher.find()) {
            int limit = Integer.parseInt(limitMatcher.group(1));
            if (limit > 100) {
                result = limitMatcher.replaceFirst("LIMIT 100");
            }
        } else {
            result = result + " LIMIT 100";
        }
        return result;
    }

    private String stripFences(String sql) {
        return sql.replaceAll("(?s)```[a-zA-Z]*", "").replace("```", "").trim();
    }

    private String stripComments(String sql) {
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noLine = noBlock.replaceAll("(?m)--.*$", " ");
        return noLine.replaceAll("(?m)#.*$", " ");
    }
}
