CREATE TABLE progression_decision_idempotency (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    result_recommendation_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_progression_decision_user_operation_key
        UNIQUE (user_id, operation, idempotency_key),
    KEY idx_progression_decision_result (result_recommendation_id),
    CONSTRAINT fk_progression_decision_user
        FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_progression_decision_result
        FOREIGN KEY (result_recommendation_id) REFERENCES progression_recommendation (id) ON DELETE RESTRICT,
    CONSTRAINT ck_progression_decision_completion CHECK (
        (result_recommendation_id IS NULL AND completed_at IS NULL)
        OR (result_recommendation_id IS NOT NULL AND completed_at IS NOT NULL)
    )
) ENGINE = InnoDB;

CREATE INDEX idx_progression_recommendation_user_created_id
    ON progression_recommendation (user_id, created_at, id);
