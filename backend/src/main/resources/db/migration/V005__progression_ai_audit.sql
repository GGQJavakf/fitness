CREATE TABLE progression_recommendation (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    exercise_id BINARY(16) NOT NULL,
    source_session_id BINARY(16) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    current_json JSON NOT NULL,
    recommended_json JSON NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    input_snapshot_json JSON NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    user_decision VARCHAR(32) NOT NULL,
    applied_plan_version_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_progression_recommendation_source UNIQUE (source_session_id, exercise_id, algorithm_version),
    KEY idx_progression_recommendation_user_decision_created (user_id, user_decision, created_at),
    CONSTRAINT fk_progression_recommendation_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_progression_recommendation_exercise FOREIGN KEY (exercise_id) REFERENCES exercise (id) ON DELETE RESTRICT,
    CONSTRAINT fk_progression_recommendation_session_user FOREIGN KEY (source_session_id, user_id) REFERENCES workout_session (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_progression_recommendation_plan_version FOREIGN KEY (applied_plan_version_id) REFERENCES training_plan_version (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE ai_interaction (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    input_schema_version VARCHAR(64) NOT NULL,
    redacted_input_digest CHAR(64) NOT NULL,
    output_schema_version VARCHAR(64) NOT NULL,
    validation_status VARCHAR(32) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    latency_ms INT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_ai_interaction_user_created (user_id, created_at),
    KEY idx_ai_interaction_validation_created (validation_status, created_at),
    CONSTRAINT fk_ai_interaction_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE domain_audit (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id BINARY(16) NOT NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_domain_audit_user_created (user_id, created_at),
    CONSTRAINT fk_domain_audit_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE outbox_event (
    id BINARY(16) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    next_attempt_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_event_status_next_attempt (status, next_attempt_at)
) ENGINE = InnoDB;
