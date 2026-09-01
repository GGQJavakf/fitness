CREATE TABLE plan_candidate (
    user_id BINARY(16) NOT NULL,
    candidate_id BINARY(16) NOT NULL,
    candidate_json JSON NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, candidate_id),
    KEY idx_plan_candidate_expiry (expires_at),
    CONSTRAINT fk_plan_candidate_user FOREIGN KEY (user_id)
        REFERENCES user_account (id) ON DELETE CASCADE
) ENGINE=InnoDB;
