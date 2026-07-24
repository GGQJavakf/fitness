CREATE TABLE privacy_export_artifact (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    generated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    payload_json JSON NOT NULL,
    PRIMARY KEY (id),
    KEY idx_privacy_export_user_expires (user_id, expires_at),
    CONSTRAINT fk_privacy_export_user FOREIGN KEY (user_id)
        REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_privacy_export_status CHECK (status IN ('READY'))
) ENGINE = InnoDB;

DELIMITER $$
CREATE TRIGGER trg_privacy_export_artifact_immutable_update
BEFORE UPDATE ON privacy_export_artifact
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'privacy export artifacts are immutable';
END$$
DELIMITER ;
