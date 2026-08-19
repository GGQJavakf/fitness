CREATE TABLE workout_set_void (
    id BINARY(16) NOT NULL,
    workout_set_id BINARY(16) NOT NULL,
    session_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_digest BINARY(32) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    applied_session_version BIGINT UNSIGNED NOT NULL,
    voided_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_workout_set_void_set UNIQUE (workout_set_id),
    CONSTRAINT uq_workout_set_void_user_idempotency UNIQUE (user_id, idempotency_key),
    KEY idx_workout_set_void_session_time (session_id, voided_at),
    CONSTRAINT fk_workout_set_void_set
        FOREIGN KEY (workout_set_id) REFERENCES workout_set (id) ON DELETE RESTRICT,
    CONSTRAINT fk_workout_set_void_session_user
        FOREIGN KEY (session_id, user_id) REFERENCES workout_session (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_workout_set_void_reason CHECK (reason = 'USER_REQUESTED')
) ENGINE = InnoDB;

DELIMITER $$
CREATE TRIGGER trg_workout_set_void_immutable_update
BEFORE UPDATE ON workout_set_void
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout set void facts are immutable';
END$$

CREATE TRIGGER trg_workout_set_void_immutable_delete
BEFORE DELETE ON workout_set_void
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout set void facts are immutable';
END$$
DELIMITER ;
