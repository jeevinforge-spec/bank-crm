-- =====================================================================
-- Banking CRM schema (MySQL 8)
-- MySQL has no sequences, so Hibernate emulates each one with a
-- single-row table. The pooled optimizer still reserves ids in blocks,
-- which keeps JDBC insert batching enabled (IDENTITY would disable it).
-- =====================================================================

CREATE TABLE IF NOT EXISTS customer_seq (next_val BIGINT NOT NULL) ENGINE = InnoDB;
INSERT INTO customer_seq (next_val) SELECT 1 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM customer_seq);

CREATE TABLE IF NOT EXISTS audit_log_seq (next_val BIGINT NOT NULL) ENGINE = InnoDB;
INSERT INTO audit_log_seq (next_val) SELECT 1 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM audit_log_seq);

CREATE TABLE IF NOT EXISTS bulk_load_error_seq (next_val BIGINT NOT NULL) ENGINE = InnoDB;
INSERT INTO bulk_load_error_seq (next_val) SELECT 1 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM bulk_load_error_seq);

CREATE TABLE IF NOT EXISTS customer (
    id                BIGINT         NOT NULL PRIMARY KEY,
    customer_number   VARCHAR(20)    NOT NULL,
    first_name        VARCHAR(60)    NOT NULL,
    last_name         VARCHAR(60)    NOT NULL,
    email             VARCHAR(120)   NOT NULL,
    phone             VARCHAR(20),
    date_of_birth     DATE           NOT NULL,
    address_line      VARCHAR(200),
    city              VARCHAR(80),
    state             VARCHAR(80),
    postal_code       VARCHAR(15),
    country           VARCHAR(60)    NOT NULL,
    account_type      VARCHAR(20)    NOT NULL,
    account_balance   DECIMAL(18, 2) NOT NULL DEFAULT 0,
    annual_income     DECIMAL(18, 2),
    credit_score      INT,
    kyc_status        VARCHAR(20)    NOT NULL,
    risk_category     VARCHAR(10)    NOT NULL,
    customer_status   VARCHAR(20)    NOT NULL,
    branch_code       VARCHAR(10)    NOT NULL,
    bulk_job_id       VARCHAR(36),
    created_at        DATETIME(6)    NOT NULL,
    updated_at        DATETIME(6)    NOT NULL,
    created_by        VARCHAR(50)    NOT NULL,
    updated_by        VARCHAR(50)    NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_customer_number UNIQUE (customer_number),
    CONSTRAINT uk_customer_email UNIQUE (email),
    CONSTRAINT ck_credit_score CHECK (credit_score IS NULL OR credit_score BETWEEN 300 AND 900),
    INDEX idx_customer_last_name (last_name),
    INDEX idx_customer_account_type (account_type),
    INDEX idx_customer_kyc_status (kyc_status),
    INDEX idx_customer_bulk_job (bulk_job_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGINT        NOT NULL PRIMARY KEY,
    event_time    DATETIME(6)   NOT NULL,
    action        VARCHAR(30)   NOT NULL,
    entity_type   VARCHAR(40)   NOT NULL,
    entity_id     VARCHAR(40),
    performed_by  VARCHAR(50)   NOT NULL,
    client_ip     VARCHAR(45),
    job_id        VARCHAR(36),
    outcome       VARCHAR(10)   NOT NULL,
    details       VARCHAR(1000),
    changes       TEXT,
    INDEX idx_audit_event_time (event_time),
    INDEX idx_audit_action (action),
    INDEX idx_audit_entity (entity_type, entity_id),
    INDEX idx_audit_job (job_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS bulk_load_job (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id          VARCHAR(36)   NOT NULL,
    source          VARCHAR(20)   NOT NULL,
    file_name       VARCHAR(255),
    status          VARCHAR(30)   NOT NULL,
    total_records   INT           NOT NULL DEFAULT 0,
    success_count   INT           NOT NULL DEFAULT 0,
    failure_count   INT           NOT NULL DEFAULT 0,
    thread_count    INT           NOT NULL,
    chunk_size      INT           NOT NULL,
    requested_by    VARCHAR(50)   NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    started_at      DATETIME(6),
    completed_at    DATETIME(6),
    duration_ms     BIGINT,
    thread_stats    VARCHAR(2000),
    error_message   VARCHAR(1000),
    CONSTRAINT uk_bulk_job_id UNIQUE (job_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS bulk_load_error (
    id               BIGINT        NOT NULL PRIMARY KEY,
    job_id           VARCHAR(36)   NOT NULL,
    row_num          INT           NOT NULL,
    customer_number  VARCHAR(40),
    error_message    VARCHAR(500)  NOT NULL,
    raw_data         VARCHAR(1000),
    INDEX idx_bulk_error_job (job_id)
) ENGINE = InnoDB;
