CREATE TABLE plan_warning_confirmation (
    token_digest VARBINARY(32) NOT NULL,
    user_id BINARY(16) NOT NULL,
    fingerprint_digest VARBINARY(32) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (token_digest),
    KEY idx_plan_warning_user_expiry (user_id, expires_at),
    KEY idx_plan_warning_expiry (expires_at),
    CONSTRAINT fk_plan_warning_user FOREIGN KEY (user_id)
        REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_plan_warning_expiry CHECK (issued_at < expires_at)
) ENGINE = InnoDB;

CREATE TABLE authentication_rate_limit_bucket (
    action VARCHAR(32) NOT NULL,
    key_digest VARBINARY(32) NOT NULL,
    bucket_start DATETIME(6) NOT NULL,
    attempts INT UNSIGNED NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (action, key_digest, bucket_start),
    KEY idx_auth_rate_limit_expiry (expires_at),
    CONSTRAINT ck_auth_rate_limit_attempts CHECK (attempts >= 1),
    CONSTRAINT ck_auth_rate_limit_expiry CHECK (bucket_start < expires_at)
) ENGINE = InnoDB;
