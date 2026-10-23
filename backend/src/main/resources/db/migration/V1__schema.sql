-- RecoverPlus schema. Money is NUMERIC (exact), never float.

-- ---------- source data (loaded from the case_03 data pack) ----------
CREATE TABLE customers (
    customer_id        VARCHAR(20) PRIMARY KEY,
    full_name          VARCHAR(200),
    mobile             VARCHAR(20),
    city               VARCHAR(100),
    preferred_language VARCHAR(20),
    occupation_type    VARCHAR(30),
    monthly_income     NUMERIC(14, 2),
    dispute_flag       BOOLEAN NOT NULL DEFAULT FALSE,
    deceased_flag      BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE loans (
    loan_id     VARCHAR(20) PRIMARY KEY,
    customer_id VARCHAR(20) NOT NULL REFERENCES customers (customer_id),
    product     VARCHAR(30),
    principal   NUMERIC(14, 2) NOT NULL,
    emi_amount  NUMERIC(14, 2) NOT NULL,
    branch      VARCHAR(100)
);

CREATE TABLE repayment_schedule (
    loan_id           VARCHAR(20) NOT NULL REFERENCES loans (loan_id),
    instalment_no     INT NOT NULL,
    due_date          DATE NOT NULL,
    emi_amount        NUMERIC(14, 2) NOT NULL,
    closing_principal NUMERIC(14, 2) NOT NULL,
    PRIMARY KEY (loan_id, instalment_no)
);

-- payment_id is the primary key, so importing the same file twice adds nothing.
CREATE TABLE payments (
    payment_id   VARCHAR(30) PRIMARY KEY,
    loan_id      VARCHAR(20) NOT NULL REFERENCES loans (loan_id),
    payment_date DATE NOT NULL,
    amount       NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    channel      VARCHAR(20),
    source       VARCHAR(40)
);
CREATE INDEX idx_payments_loan ON payments (loan_id, payment_date);

CREATE TABLE contacts (
    contact_id   VARCHAR(40) PRIMARY KEY,
    loan_id      VARCHAR(20) NOT NULL REFERENCES loans (loan_id),
    contact_ts   TIMESTAMPTZ NOT NULL,
    channel      VARCHAR(20) NOT NULL,
    outcome      VARCHAR(20),
    ptp_date     DATE,
    ptp_amount   NUMERIC(14, 2),
    collector_id VARCHAR(20),
    notes        TEXT,
    ptp_status   VARCHAR(10),
    source       VARCHAR(40)
);
CREATE INDEX idx_contacts_loan ON contacts (loan_id, contact_ts);

CREATE TABLE app_users (
    user_id        VARCHAR(20) PRIMARY KEY,
    full_name      VARCHAR(200),
    role           VARCHAR(40) NOT NULL,
    queue          VARCHAR(40),
    city           VARCHAR(100),
    daily_capacity INT NOT NULL DEFAULT 0,
    password_hash  VARCHAR(64) NOT NULL
);

-- ---------- configuration ----------
CREATE TABLE rules_config (
    config_key   VARCHAR(200) PRIMARY KEY,
    config_value TEXT,
    updated_by   VARCHAR(20),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- day-end results ----------
CREATE TABLE day_end_runs (
    run_id       BIGSERIAL PRIMARY KEY,
    as_of_date   DATE NOT NULL,
    run_by       VARCHAR(20),
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    loans        INT,
    ai_available BOOLEAN,
    status       VARCHAR(20) NOT NULL
);

-- One row per loan per as-of date. Re-running a date replaces its rows in one transaction.
CREATE TABLE loan_positions (
    as_of_date        DATE NOT NULL,
    loan_id           VARCHAR(20) NOT NULL REFERENCES loans (loan_id),
    dpd               INT NOT NULL,
    bucket            VARCHAR(10) NOT NULL,
    overdue_amount    NUMERIC(14, 2) NOT NULL,
    penal_charges     NUMERIC(14, 2) NOT NULL,
    total_dues        NUMERIC(14, 2) NOT NULL,
    contact_strategy  VARCHAR(20) NOT NULL,
    queue             VARCHAR(20),
    codes             VARCHAR(200),
    worklist_rank     INT,
    assigned_to       VARCHAR(20),
    broken_ptp_count  INT NOT NULL DEFAULT 0,
    p_miss_next       NUMERIC(6, 4),
    expected_recovery NUMERIC(14, 2),
    ai_strategy       VARCHAR(20),
    ai_queue          VARCHAR(20),
    ai_codes          VARCHAR(200),
    ai_rank           INT,
    top_factors       VARCHAR(300),
    PRIMARY KEY (as_of_date, loan_id)
);
CREATE INDEX idx_positions_queue ON loan_positions (as_of_date, queue, worklist_rank);
CREATE INDEX idx_positions_assignee ON loan_positions (as_of_date, assigned_to);

-- ---------- settlements (maker-checker) ----------
CREATE TABLE settlements (
    request_id      VARCHAR(30) PRIMARY KEY,
    loan_id         VARCHAR(20) NOT NULL REFERENCES loans (loan_id),
    request_date    DATE NOT NULL,
    proposed_by     VARCHAR(20) NOT NULL,
    offer_amount    NUMERIC(14, 2) NOT NULL CHECK (offer_amount > 0),
    dues_at_request NUMERIC(14, 2) NOT NULL,
    discount_pct    NUMERIC(6, 2) NOT NULL,
    eligible        BOOLEAN NOT NULL,
    route           VARCHAR(30) NOT NULL,
    reason_code     VARCHAR(40),
    status          VARCHAR(30) NOT NULL,
    proposal_reason TEXT,
    decided_by      VARCHAR(20),
    decided_on      DATE,
    decided_at      TIMESTAMPTZ,
    decision_comment TEXT,
    letter_text     TEXT,
    valid_until     DATE,
    version         INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_settlements_status ON settlements (status);

-- ---------- outbox and letters (written, never sent) ----------
CREATE TABLE outbox (
    id         BIGSERIAL PRIMARY KEY,
    as_of_date DATE NOT NULL,
    loan_id    VARCHAR(20) NOT NULL,
    channel    VARCHAR(20) NOT NULL,
    language   VARCHAR(20),
    message    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_date ON outbox (as_of_date);

CREATE TABLE letters (
    id          BIGSERIAL PRIMARY KEY,
    as_of_date  DATE NOT NULL,
    loan_id     VARCHAR(20) NOT NULL,
    letter_type VARCHAR(30) NOT NULL,
    body        TEXT NOT NULL
);
CREATE INDEX idx_letters_date ON letters (as_of_date);

-- ---------- audit trail (append-only, see V2) ----------
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    ts          TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id     VARCHAR(20),
    role        VARCHAR(40),
    entity      VARCHAR(30) NOT NULL,
    entity_id   VARCHAR(60),
    action      VARCHAR(60) NOT NULL,
    before_json TEXT,
    after_json  TEXT,
    comment     TEXT
);
CREATE INDEX idx_audit_entity ON audit_log (entity, entity_id, ts);
