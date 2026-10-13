-- ============================================================================
-- Secure Bank - AI Banking RAG System
-- MySQL (SQL) schema. This replaces the SQLite file used in the original draft.
-- Executed automatically by the `db` service on first container start.
--
-- NOTE: demo users (alice/password123, admin/admin123) and their accounts,
-- cards and loans are seeded by the backend on first boot (DataInitializer),
-- so the BCrypt password hashes are always valid. Tables are created here and
-- Hibernate runs with ddl-auto=update to reconcile any differences.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS banking
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE banking;

CREATE TABLE IF NOT EXISTS users (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(100) NOT NULL,
    password    VARCHAR(255) NOT NULL,
    roles       VARCHAR(100) NOT NULL,
    created_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS accounts (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    user_id        BIGINT        NOT NULL,
    account_number VARCHAR(32)   NOT NULL,
    currency       VARCHAR(8)    NOT NULL,
    balance        DECIMAL(18,2) NOT NULL DEFAULT 0,
    updated_at     DATETIME(6)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_accounts_number (account_number),
    KEY idx_accounts_user (user_id),
    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS cards (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id      BIGINT        NOT NULL,
    type         VARCHAR(16)   NOT NULL,
    last4        VARCHAR(4)    NOT NULL,
    credit_limit DECIMAL(18,2) NULL,
    outstanding  DECIMAL(18,2) NULL,
    status       VARCHAR(32)   NULL,
    updated_at   DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_cards_user (user_id),
    CONSTRAINT fk_cards_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS loans (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL,
    product_type  VARCHAR(64)   NOT NULL,
    principal     DECIMAL(18,2) NOT NULL,
    interest_rate DECIMAL(5,2)  NOT NULL,
    outstanding   DECIMAL(18,2) NOT NULL,
    status        VARCHAR(32)   NULL,
    updated_at    DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_loans_user (user_id),
    CONSTRAINT fk_loans_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;
