ALTER TABLE training_plan_version
    ADD CONSTRAINT uq_training_plan_version_receipt UNIQUE (id, plan_id, version_no);

CREATE TABLE plan_candidate_commit_receipt (
    user_id BINARY(16) NOT NULL,
    key_digest VARBINARY(32) NOT NULL,
    payload_digest VARBINARY(32) NOT NULL,
    plan_id BINARY(16) NULL,
    version_no INT UNSIGNED NULL,
    version_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (user_id, key_digest),
    KEY idx_plan_candidate_commit_completed (completed_at),
    CONSTRAINT fk_plan_candidate_commit_user FOREIGN KEY (user_id)
        REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_candidate_commit_plan FOREIGN KEY (plan_id, user_id)
        REFERENCES training_plan (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_candidate_commit_version FOREIGN KEY (version_id, plan_id, version_no)
        REFERENCES training_plan_version (id, plan_id, version_no) ON DELETE RESTRICT,
    CONSTRAINT ck_plan_candidate_commit_result CHECK (
        (plan_id IS NULL AND version_no IS NULL AND version_id IS NULL AND completed_at IS NULL)
        OR
        (plan_id IS NOT NULL AND version_no >= 1 AND version_id IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_plan_candidate_commit_completion CHECK (
        completed_at IS NULL OR completed_at >= created_at
    )
) ENGINE = InnoDB;

DELIMITER $$
CREATE TRIGGER trg_plan_candidate_commit_receipt_controlled_update
BEFORE UPDATE ON plan_candidate_commit_receipt
FOR EACH ROW
BEGIN
    IF NOT (NEW.user_id <=> OLD.user_id)
            OR NOT (NEW.key_digest <=> OLD.key_digest)
            OR NOT (NEW.payload_digest <=> OLD.payload_digest)
            OR OLD.completed_at IS NOT NULL
            OR OLD.plan_id IS NOT NULL
            OR OLD.version_no IS NOT NULL
            OR OLD.version_id IS NOT NULL
            OR NEW.plan_id IS NULL
            OR NEW.version_no IS NULL
            OR NEW.version_id IS NULL
            OR NEW.completed_at IS NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'candidate commit receipt may only transition once to completed';
    END IF;
END$$

CREATE TRIGGER trg_plan_candidate_commit_receipt_immutable_delete
BEFORE DELETE ON plan_candidate_commit_receipt
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'candidate commit receipts are immutable';
END$$
DELIMITER ;
