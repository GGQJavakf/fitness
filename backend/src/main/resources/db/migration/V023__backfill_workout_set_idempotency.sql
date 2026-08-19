-- V012 added nullable idempotency metadata so already-persisted workout facts could survive
-- that rollout. Backfill those legacy rows before making the runtime contract non-null.
DROP TRIGGER trg_workout_set_fact_immutable;

CREATE TEMPORARY TABLE tmp_workout_set_legacy_idempotency (
    workout_set_id BINARY(16) NOT NULL,
    stable_session_sequence BIGINT UNSIGNED NOT NULL,
    migration_session_version BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (workout_set_id)
) ENGINE = InnoDB;

INSERT INTO tmp_workout_set_legacy_idempotency
    (workout_set_id, stable_session_sequence, migration_session_version)
SELECT ranked.workout_set_id, ranked.stable_session_sequence, ranked.migration_session_version
FROM (
    SELECT wset.id AS workout_set_id,
           ROW_NUMBER() OVER (
               PARTITION BY ws.id
               ORDER BY snapshot.exercise_order, wset.set_order, wset.id
           ) AS stable_session_sequence,
           ws.sync_version AS migration_session_version
    FROM workout_set wset
    JOIN workout_exercise_snapshot snapshot ON snapshot.id = wset.session_exercise_id
    JOIN workout_session ws ON ws.id = snapshot.session_id
) ranked;

UPDATE workout_set wset
JOIN tmp_workout_set_legacy_idempotency legacy ON legacy.workout_set_id = wset.id
SET wset.client_operation_seq = CASE
        WHEN wset.client_operation_seq IS NULL OR wset.client_operation_seq = 0
            THEN legacy.stable_session_sequence
        ELSE wset.client_operation_seq
    END,
    -- Explicitly versioned legacy identity digest. The immutable fact id makes this
    -- deterministic across retries and independent of JSON serialization order.
    wset.payload_digest = COALESCE(
        wset.payload_digest,
        UNHEX(SHA2(CONCAT(
            'legacy-workout-set-idempotency-v1|', LOWER(HEX(wset.id))
        ), 256))),
    -- The migration-time session version is the strongest surviving historical bound.
    -- A zero-version legacy session falls back to the stable per-session fact sequence.
    wset.applied_session_version = COALESCE(
        wset.applied_session_version,
        CASE
            WHEN legacy.migration_session_version > 0 THEN legacy.migration_session_version
            ELSE legacy.stable_session_sequence
        END)
WHERE wset.client_operation_seq IS NULL
   OR wset.client_operation_seq = 0
   OR wset.payload_digest IS NULL
   OR wset.applied_session_version IS NULL;

ALTER TABLE workout_set
    MODIFY COLUMN client_operation_seq BIGINT UNSIGNED NOT NULL,
    MODIFY COLUMN payload_digest BINARY(32) NOT NULL,
    MODIFY COLUMN applied_session_version BIGINT UNSIGNED NOT NULL,
    ADD CONSTRAINT ck_workout_set_client_operation_seq CHECK (client_operation_seq >= 1);

DROP TEMPORARY TABLE tmp_workout_set_legacy_idempotency;

-- Preserve the complete V022/V020 correction contract after the temporary backfill bypass.
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
            OR NOT (NEW.safety_flag <=> OLD.safety_flag)
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
