CREATE TABLE privacy_deletion_request (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    status VARCHAR(48) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    active_user_id BINARY(16) GENERATED ALWAYS AS (
        CASE
            WHEN status IN ('REQUESTED', 'ACCESS_REVOKED', 'BUSINESS_DATA_ANONYMIZED', 'RETENTION_SEPARATED')
            THEN user_id
            ELSE NULL
        END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uq_privacy_deletion_active_user (active_user_id),
    KEY idx_privacy_deletion_user_requested (user_id, requested_at DESC),
    CONSTRAINT fk_privacy_deletion_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_privacy_deletion_status CHECK (status IN (
        'REQUESTED', 'ACCESS_REVOKED', 'BUSINESS_DATA_ANONYMIZED',
        'RETENTION_SEPARATED', 'COMPLETED', 'REJECTED'))
) ENGINE = InnoDB;

CREATE TABLE privacy_required_retention (
    id BINARY(16) NOT NULL,
    deletion_request_id BINARY(16) NOT NULL,
    user_reference_digest VARBINARY(64) NOT NULL,
    retention_category VARCHAR(48) NOT NULL,
    payload_digest VARBINARY(64) NOT NULL,
    retained_until DATETIME(6) NULL,
    policy_version VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_privacy_retention_request_category (deletion_request_id, retention_category),
    KEY idx_privacy_retention_until (retained_until),
    CONSTRAINT fk_privacy_retention_request FOREIGN KEY (deletion_request_id)
        REFERENCES privacy_deletion_request (id) ON DELETE RESTRICT,
    CONSTRAINT ck_privacy_retention_category CHECK (retention_category IN ('SECURITY_AUDIT', 'LEGAL_HOLD'))
) ENGINE = InnoDB;

DELIMITER $$
CREATE TRIGGER trg_privacy_required_retention_immutable_update
BEFORE UPDATE ON privacy_required_retention
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'privacy required retention is immutable';
END$$

CREATE TRIGGER trg_privacy_required_retention_immutable_delete
BEFORE DELETE ON privacy_required_retention
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'privacy required retention is immutable';
END$$
DELIMITER ;
