ALTER TABLE privacy_required_retention
    MODIFY COLUMN retained_until DATETIME(6) NOT NULL,
    ADD COLUMN hold_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN hold_released_at DATETIME(6) NULL,
    ADD COLUMN disposition_status VARCHAR(16) NOT NULL DEFAULT 'RETAINED',
    ADD COLUMN disposed_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_privacy_retention_hold_status
        CHECK (hold_status IN ('ACTIVE', 'RELEASED')),
    ADD CONSTRAINT ck_privacy_retention_disposition_status
        CHECK (disposition_status IN ('RETAINED', 'PURGED')),
    ADD CONSTRAINT ck_privacy_retention_hold_release_time
        CHECK ((hold_status = 'ACTIVE' AND hold_released_at IS NULL)
            OR (hold_status = 'RELEASED' AND hold_released_at IS NOT NULL)),
    ADD CONSTRAINT ck_privacy_retention_disposition_time
        CHECK ((disposition_status = 'RETAINED' AND disposed_at IS NULL)
            OR (disposition_status = 'PURGED' AND disposed_at IS NOT NULL));

CREATE TABLE privacy_retention_lifecycle_audit (
    id BINARY(16) NOT NULL,
    retention_id BINARY(16) NOT NULL,
    action VARCHAR(32) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_privacy_retention_audit_retention_time (retention_id, occurred_at),
    CONSTRAINT fk_privacy_retention_audit_retention FOREIGN KEY (retention_id)
        REFERENCES privacy_required_retention (id) ON DELETE RESTRICT,
    CONSTRAINT ck_privacy_retention_audit_action CHECK (action IN ('HOLD_RELEASED', 'PAYLOAD_PURGED'))
) ENGINE = InnoDB;

DROP TRIGGER trg_privacy_required_retention_immutable_update;

DELIMITER $$
CREATE TRIGGER trg_privacy_required_retention_require_expiry_insert
BEFORE INSERT ON privacy_required_retention
FOR EACH ROW
BEGIN
    IF NEW.retained_until IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'retention expiry must be selected by an approved policy';
    END IF;
END$$

CREATE TRIGGER trg_privacy_required_retention_controlled_update
BEFORE UPDATE ON privacy_required_retention
FOR EACH ROW
BEGIN
    IF NOT (OLD.id <=> NEW.id)
        OR NOT (OLD.deletion_request_id <=> NEW.deletion_request_id)
        OR NOT (OLD.user_reference_digest <=> NEW.user_reference_digest)
        OR NOT (OLD.retention_category <=> NEW.retention_category)
        OR NOT (OLD.retained_until <=> NEW.retained_until)
        OR NOT (OLD.policy_version <=> NEW.policy_version)
        OR NOT (OLD.created_at <=> NEW.created_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'retention facts are immutable';
    END IF;

    IF OLD.hold_status = 'ACTIVE' AND NEW.hold_status = 'RELEASED'
        AND OLD.disposition_status = NEW.disposition_status
        AND OLD.payload_digest = NEW.payload_digest
        AND NEW.hold_released_at IS NOT NULL
        AND OLD.disposed_at <=> NEW.disposed_at THEN
        SET NEW.hold_released_at = COALESCE(NEW.hold_released_at, UTC_TIMESTAMP(6));
    ELSEIF OLD.hold_status = 'RELEASED' AND NEW.hold_status = 'RELEASED'
        AND OLD.disposition_status = 'RETAINED' AND NEW.disposition_status = 'PURGED'
        AND OLD.hold_released_at <=> NEW.hold_released_at
        AND NEW.disposed_at IS NOT NULL
        AND OLD.retained_until <= UTC_TIMESTAMP(6)
        AND NEW.payload_digest = UNHEX(SHA2('', 256)) THEN
        SET NEW.disposed_at = COALESCE(NEW.disposed_at, UTC_TIMESTAMP(6));
    ELSE
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invalid retention lifecycle transition';
    END IF;
END$$

CREATE TRIGGER trg_privacy_required_retention_lifecycle_audit
AFTER UPDATE ON privacy_required_retention
FOR EACH ROW
BEGIN
    INSERT INTO privacy_retention_lifecycle_audit (
        id, retention_id, action, policy_version, occurred_at)
    VALUES (
        UUID_TO_BIN(UUID()),
        NEW.id,
        CASE WHEN OLD.hold_status <> NEW.hold_status THEN 'HOLD_RELEASED' ELSE 'PAYLOAD_PURGED' END,
        NEW.policy_version,
        UTC_TIMESTAMP(6));
END$$

CREATE TRIGGER trg_privacy_retention_lifecycle_audit_immutable_update
BEFORE UPDATE ON privacy_retention_lifecycle_audit
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'retention lifecycle audit is immutable';
END$$

CREATE TRIGGER trg_privacy_retention_lifecycle_audit_immutable_delete
BEFORE DELETE ON privacy_retention_lifecycle_audit
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'retention lifecycle audit is immutable';
END$$
DELIMITER ;
