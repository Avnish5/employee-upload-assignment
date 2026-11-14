CREATE TABLE upload (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    filename        VARCHAR(255)  NOT NULL,
    source          VARCHAR(16)   NOT NULL,              -- USER | PRELOAD
    status          VARCHAR(16)   NOT NULL,              -- QUEUED | PROCESSING | COMPLETED | FAILED
    error_message   VARCHAR(1000) NULL,
    total_rows      INT           NOT NULL DEFAULT 0,
    processed_rows  INT           NOT NULL DEFAULT 0,
    inserted_rows   INT           NOT NULL DEFAULT 0,
    skipped_rows    INT           NOT NULL DEFAULT 0,
    rejected_rows   INT           NOT NULL DEFAULT 0,
    created_at      DATETIME(3)   NOT NULL,
    started_at      DATETIME(3)   NULL,
    finished_at     DATETIME(3)   NULL
);

CREATE TABLE employee (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    first_name       VARCHAR(100) NOT NULL,
    last_name        VARCHAR(100) NOT NULL,
    dob              DATE         NOT NULL,
    gender           CHAR(1)      NOT NULL,
    mobile           CHAR(10)     NOT NULL,
    -- binary collation so DB uniqueness matches the application's exact (already lower-cased) comparison;
    -- the default ai_ci collation would treat 'José' and 'Jose' as equal and disagree with the app
    email            VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    department       VARCHAR(100) NOT NULL,
    designation      VARCHAR(100) NOT NULL,
    date_of_joining  DATE         NOT NULL,
    name_dob_key     VARCHAR(220) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    upload_id        BIGINT       NOT NULL,
    source_row       INT          NOT NULL,
    created_at       DATETIME(3)  NOT NULL,
    CONSTRAINT uq_employee_name_dob UNIQUE (name_dob_key),
    CONSTRAINT uq_employee_mobile   UNIQUE (mobile),
    CONSTRAINT uq_employee_email    UNIQUE (email),
    CONSTRAINT fk_employee_upload   FOREIGN KEY (upload_id) REFERENCES upload (id),
    INDEX ix_employee_name (last_name, first_name, id),
    INDEX ix_employee_department (department)
);

CREATE TABLE upload_error (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    upload_id       BIGINT        NOT NULL,
    row_num         INT           NOT NULL,
    error_codes     VARCHAR(200)  NOT NULL,
    error_messages  VARCHAR(2000) NOT NULL,
    raw_values      JSON          NOT NULL,
    CONSTRAINT fk_upload_error_upload FOREIGN KEY (upload_id) REFERENCES upload (id),
    INDEX ix_upload_error_upload_row (upload_id, row_num)
);
