-- =====================================================================
-- Banking CRM schema (H2)
-- Sequences use INCREMENT BY = Hibernate allocationSize so ids are
-- handed out in pooled blocks (one DB round trip per block, not per row).
-- =====================================================================

CREATE SEQUENCE IF NOT EXISTS customer_seq START WITH 1 INCREMENT BY 500;
CREATE SEQUENCE IF NOT EXISTS audit_log_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS bulk_load_error_seq START WITH 1 INCREMENT BY 100;

-- ---------------------------------------------------------------------
-- Customer master data
-- ---------------------------------------------------------------------
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
    created_at        TIMESTAMP      NOT NULL,
    updated_at        TIMESTAMP      NOT NULL,
    created_by        VARCHAR(50)    NOT NULL,
    updated_by        VARCHAR(50)    NOT NULL,
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_customer_number UNIQUE (customer_number),
    CONSTRAINT uk_customer_email UNIQUE (email),
    CONSTRAINT ck_credit_score CHECK (credit_score IS NULL OR credit_score BETWEEN 300 AND 900)
);
CREATE INDEX IF NOT EXISTS idx_customer_last_name ON customer (last_name);
CREATE INDEX IF NOT EXISTS idx_customer_account_type ON customer (account_type);
CREATE INDEX IF NOT EXISTS idx_customer_kyc_status ON customer (kyc_status);
CREATE INDEX IF NOT EXISTS idx_customer_bulk_job ON customer (bulk_job_id);

-- ---------------------------------------------------------------------
-- Audit trail: every create/update/delete and every bulk-load event
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGINT        NOT NULL PRIMARY KEY,
    event_time    TIMESTAMP     NOT NULL,
    action        VARCHAR(30)   NOT NULL,
    entity_type   VARCHAR(40)   NOT NULL,
    entity_id     VARCHAR(40),
    performed_by  VARCHAR(50)   NOT NULL,
    client_ip     VARCHAR(45),
    job_id        VARCHAR(36),
    outcome       VARCHAR(10)   NOT NULL,
    details       VARCHAR(1000),
    changes       CLOB
);
CREATE INDEX IF NOT EXISTS idx_audit_event_time ON audit_log (event_time);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log (action);
CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_job ON audit_log (job_id);

-- ---------------------------------------------------------------------
-- Bulk load job tracking
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bulk_load_job (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
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
    created_at      TIMESTAMP     NOT NULL,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    duration_ms     BIGINT,
    thread_stats    VARCHAR(2000),
    error_message   VARCHAR(1000),
    CONSTRAINT uk_bulk_job_id UNIQUE (job_id)
);

CREATE TABLE IF NOT EXISTS bulk_load_error (
    id               BIGINT        NOT NULL PRIMARY KEY,
    job_id           VARCHAR(36)   NOT NULL,
    row_num          INT           NOT NULL,
    customer_number  VARCHAR(40),
    error_message    VARCHAR(500)  NOT NULL,
    raw_data         VARCHAR(1000)
);
CREATE INDEX IF NOT EXISTS idx_bulk_error_job ON bulk_load_error (job_id);
