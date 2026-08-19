CREATE TABLE privacy_reauthentication_proof (
    proof_digest VARBINARY(32) NOT NULL,
    user_id BINARY(16) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (proof_digest),
    KEY idx_privacy_reauth_user_expiry (user_id, expires_at),
    KEY idx_privacy_reauth_expiry (expires_at),
    KEY idx_privacy_reauth_consumed (consumed_at),
    CONSTRAINT fk_privacy_reauth_user FOREIGN KEY (user_id)
        REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_privacy_reauth_expiry CHECK (issued_at < expires_at)
) ENGINE = InnoDB;

CREATE TABLE privacy_rate_limit_bucket (
    user_id BINARY(16) NOT NULL,
    action VARCHAR(64) NOT NULL,
    bucket_start DATETIME(6) NOT NULL,
    attempts INT UNSIGNED NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, action, bucket_start),
    KEY idx_privacy_rate_limit_expiry (expires_at),
    CONSTRAINT fk_privacy_rate_limit_user FOREIGN KEY (user_id)
        REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_privacy_rate_limit_attempts CHECK (attempts >= 1),
    CONSTRAINT ck_privacy_rate_limit_expiry CHECK (bucket_start < expires_at)
) ENGINE = InnoDB;
