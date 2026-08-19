CREATE TABLE workout_completion_outbox (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    session_id BINARY(16) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claimed_until DATETIME(6) NULL,
    claim_token BINARY(16) NULL,
    processed_at DATETIME(6) NULL,
    last_error VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_workout_completion_outbox_session_event UNIQUE (session_id, event_type),
    KEY idx_workout_completion_outbox_claim (status, next_attempt_at, claimed_until, created_at),
    CONSTRAINT fk_workout_completion_outbox_user
        FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_workout_completion_outbox_session_user
        FOREIGN KEY (session_id, user_id) REFERENCES workout_session (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_workout_completion_outbox_event CHECK (event_type = 'WORKOUT_COMPLETED'),
    CONSTRAINT ck_workout_completion_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED')),
    CONSTRAINT ck_workout_completion_outbox_claim CHECK (
        (status = 'PROCESSING' AND claimed_until IS NOT NULL AND claim_token IS NOT NULL AND processed_at IS NULL)
        OR (status = 'PENDING' AND claimed_until IS NULL AND claim_token IS NULL AND processed_at IS NULL)
        OR (status = 'PROCESSED' AND claimed_until IS NULL AND claim_token IS NULL AND processed_at IS NOT NULL)
    )
) ENGINE = InnoDB;
