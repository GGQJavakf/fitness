ALTER TABLE workout_set
    ADD COLUMN client_operation_seq BIGINT UNSIGNED NULL AFTER client_set_key,
    ADD COLUMN payload_digest BINARY(32) NULL AFTER anomaly_status,
    ADD COLUMN applied_session_version BIGINT UNSIGNED NULL AFTER payload_digest,
    ADD KEY idx_workout_set_operation_seq (session_exercise_id, client_operation_seq);

DROP TRIGGER trg_workout_set_fact_immutable;

DELIMITER $$
CREATE TRIGGER trg_workout_set_fact_immutable
BEFORE UPDATE ON workout_set
FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id)
            OR NOT (NEW.session_exercise_id <=> OLD.session_exercise_id)
            OR NOT (NEW.client_set_key <=> OLD.client_set_key)
            OR NOT (NEW.client_operation_seq <=> OLD.client_operation_seq)
            OR NOT (NEW.set_type <=> OLD.set_type)
            OR NOT (NEW.set_order <=> OLD.set_order)
            OR NOT (NEW.target_json <=> OLD.target_json)
            OR NOT (NEW.unit <=> OLD.unit)
            OR NOT (NEW.payload_digest <=> OLD.payload_digest)
            OR NOT (NEW.applied_session_version <=> OLD.applied_session_version) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout set facts are immutable';
    END IF;
END$$
DELIMITER ;
