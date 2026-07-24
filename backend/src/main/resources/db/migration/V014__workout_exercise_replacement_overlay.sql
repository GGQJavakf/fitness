ALTER TABLE workout_exercise_snapshot
    ADD COLUMN replacement_snapshot_json JSON NULL AFTER exercise_snapshot_json,
    ADD COLUMN replacement_revision BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER replacement_snapshot_json;

CREATE INDEX idx_workout_session_user_started_id
    ON workout_session (user_id, started_at DESC, id DESC);
