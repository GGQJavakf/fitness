ALTER TABLE progression_recommendation
    ADD COLUMN accepted_weight DECIMAL(10,2) NULL AFTER user_decision,
    ADD COLUMN dismissal_reason VARCHAR(64) NULL AFTER accepted_weight,
    ADD COLUMN decision_idempotency_key VARCHAR(128) NULL AFTER dismissal_reason,
    ADD COLUMN decided_at DATETIME(6) NULL AFTER decision_idempotency_key,
    ADD CONSTRAINT ck_progression_recommendation_decision_metadata CHECK (
        (user_decision = 'PENDING'
            AND accepted_weight IS NULL AND dismissal_reason IS NULL
            AND decision_idempotency_key IS NULL AND decided_at IS NULL
            AND applied_plan_id IS NULL AND applied_plan_version_id IS NULL)
        OR (user_decision IN ('APPLIED', 'MODIFIED')
            AND accepted_weight IS NOT NULL AND dismissal_reason IS NULL
            AND decision_idempotency_key IS NOT NULL AND decided_at IS NOT NULL
            AND applied_plan_id IS NOT NULL AND applied_plan_version_id IS NOT NULL)
        OR (user_decision = 'DISMISSED'
            AND accepted_weight IS NULL AND dismissal_reason IS NOT NULL
            AND decision_idempotency_key IS NULL AND decided_at IS NOT NULL
            AND applied_plan_id IS NULL AND applied_plan_version_id IS NULL)
    ),
    ADD CONSTRAINT uq_progression_recommendation_user_idempotency
        UNIQUE (user_id, decision_idempotency_key);
