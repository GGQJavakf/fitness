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
            OR NOT (NEW.unit <=> OLD.unit) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout set facts are immutable';
    END IF;

    IF NOT (NEW.actual_weight <=> OLD.actual_weight)
            OR NOT (NEW.actual_reps <=> OLD.actual_reps)
            OR NOT (NEW.remaining_reps <=> OLD.remaining_reps)
            OR NOT (NEW.completion_status <=> OLD.completion_status)
            OR NOT (NEW.completed_at <=> OLD.completed_at)
            OR NOT (NEW.anomaly_status <=> OLD.anomaly_status)
            OR NOT (NEW.payload_digest <=> OLD.payload_digest)
            OR NOT (NEW.applied_session_version <=> OLD.applied_session_version)
            OR NOT (NEW.server_revision <=> OLD.server_revision) THEN
        IF NEW.server_revision <> OLD.server_revision + 1 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'workout set correction requires next revision';
        END IF;
        IF NOT EXISTS (
                SELECT 1
                FROM workout_set_revision revision_fact
                WHERE revision_fact.workout_set_id = OLD.id
                  AND revision_fact.revision_no = NEW.server_revision) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'workout set correction requires revision audit';
        END IF;
    END IF;
END$$
DELIMITER ;
