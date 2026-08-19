CREATE TABLE workout_recovery_confirmation (
    token_digest VARBINARY(32) NOT NULL,
    user_id BINARY(16) NOT NULL,
    plan_id BINARY(16) NOT NULL,
    plan_version_no INT UNSIGNED NOT NULL,
    training_day_code VARCHAR(128) NOT NULL,
    client_session_key VARCHAR(128) NOT NULL,
    assessment_fingerprint VARBINARY(32) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (token_digest),
    KEY idx_workout_recovery_confirmation_user_expiry (user_id, expires_at),
    KEY idx_workout_recovery_confirmation_expiry (expires_at),
    CONSTRAINT fk_workout_recovery_confirmation_plan_user FOREIGN KEY (plan_id, user_id)
        REFERENCES training_plan (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_workout_recovery_confirmation_plan_version FOREIGN KEY (plan_id, plan_version_no)
        REFERENCES training_plan_version (plan_id, version_no) ON DELETE RESTRICT,
    CONSTRAINT ck_workout_recovery_confirmation_expiry CHECK (issued_at < expires_at),
    CONSTRAINT ck_workout_recovery_confirmation_consumed CHECK (
        consumed_at IS NULL OR consumed_at >= issued_at
    )
) ENGINE = InnoDB;
