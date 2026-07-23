CREATE TABLE workout_session (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    plan_id BINARY(16) NOT NULL,
    plan_version_id BINARY(16) NOT NULL,
    training_day_id BINARY(16) NOT NULL,
    client_session_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    sync_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_workout_session_user_key UNIQUE (user_id, client_session_key),
    CONSTRAINT uq_workout_session_id_user UNIQUE (id, user_id),
    CONSTRAINT uq_workout_session_id_version UNIQUE (id, plan_version_id),
    CONSTRAINT uq_workout_session_id_day_version UNIQUE (id, training_day_id, plan_version_id),
    KEY idx_workout_session_user_started (user_id, started_at DESC),
    KEY idx_workout_session_user_status (user_id, status),
    CONSTRAINT fk_workout_session_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_workout_session_plan_user FOREIGN KEY (plan_id, user_id) REFERENCES training_plan (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_workout_session_version_plan FOREIGN KEY (plan_version_id, plan_id) REFERENCES training_plan_version (id, plan_id) ON DELETE RESTRICT,
    CONSTRAINT fk_workout_session_day_version FOREIGN KEY (training_day_id, plan_version_id) REFERENCES training_day (id, plan_version_id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE workout_exercise_snapshot (
    id BINARY(16) NOT NULL,
    session_id BINARY(16) NOT NULL,
    source_plan_exercise_id BINARY(16) NOT NULL,
    source_training_day_id BINARY(16) NOT NULL,
    source_plan_version_id BINARY(16) NOT NULL,
    exercise_order SMALLINT UNSIGNED NOT NULL,
    exercise_snapshot_json JSON NOT NULL,
    prescription_snapshot_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_workout_snapshot_session_order UNIQUE (session_id, exercise_order),
    KEY idx_workout_snapshot_source_plan_exercise (source_plan_exercise_id),
    CONSTRAINT fk_workout_snapshot_session_source FOREIGN KEY (session_id, source_training_day_id, source_plan_version_id) REFERENCES workout_session (id, training_day_id, plan_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_workout_snapshot_plan_source FOREIGN KEY (source_plan_exercise_id, source_training_day_id, source_plan_version_id) REFERENCES plan_exercise (id, training_day_id, plan_version_id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE workout_set (
    id BINARY(16) NOT NULL,
    session_exercise_id BINARY(16) NOT NULL,
    client_set_key VARCHAR(128) NOT NULL,
    set_type VARCHAR(32) NOT NULL,
    set_order SMALLINT UNSIGNED NOT NULL,
    target_json JSON NOT NULL,
    actual_weight DECIMAL(8,3) NULL,
    unit VARCHAR(8) NOT NULL DEFAULT 'KG',
    actual_reps SMALLINT UNSIGNED NULL,
    remaining_reps SMALLINT UNSIGNED NULL,
    completion_status VARCHAR(32) NOT NULL,
    completed_at DATETIME(6) NULL,
    server_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    anomaly_status VARCHAR(32) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_workout_set_client_key UNIQUE (session_exercise_id, client_set_key),
    KEY idx_workout_set_session_exercise_order (session_exercise_id, set_order),
    CONSTRAINT fk_workout_set_snapshot FOREIGN KEY (session_exercise_id) REFERENCES workout_exercise_snapshot (id) ON DELETE RESTRICT,
    CONSTRAINT ck_workout_set_unit CHECK (unit = 'KG'),
    CONSTRAINT ck_workout_set_weight CHECK (actual_weight IS NULL OR actual_weight >= 0)
) ENGINE = InnoDB;

CREATE TABLE workout_set_revision (
    id BINARY(16) NOT NULL,
    workout_set_id BINARY(16) NOT NULL,
    revision_no INT UNSIGNED NOT NULL,
    before_json JSON NOT NULL,
    after_json JSON NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_workout_set_revision_number UNIQUE (workout_set_id, revision_no),
    CONSTRAINT fk_workout_set_revision_set FOREIGN KEY (workout_set_id) REFERENCES workout_set (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE sync_conflict (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_key VARCHAR(128) NOT NULL,
    local_payload_json JSON NOT NULL,
    server_payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_sync_conflict_user_status_created (user_id, status, created_at),
    CONSTRAINT fk_sync_conflict_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

DELIMITER $$
CREATE TRIGGER trg_workout_session_plan_version_must_be_sealed
BEFORE INSERT ON workout_session
FOR EACH ROW
BEGIN
    IF EXISTS (
        SELECT 1 FROM training_plan_version
        WHERE id = NEW.plan_version_id AND plan_id = NEW.plan_id AND sealed_at IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout session plan version must be sealed';
    END IF;
END$$

CREATE TRIGGER trg_workout_session_source_immutable
BEFORE UPDATE ON workout_session
FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id)
            OR NOT (NEW.user_id <=> OLD.user_id)
            OR NOT (NEW.plan_id <=> OLD.plan_id)
            OR NOT (NEW.plan_version_id <=> OLD.plan_version_id)
            OR NOT (NEW.training_day_id <=> OLD.training_day_id)
            OR NOT (NEW.client_session_key <=> OLD.client_session_key)
            OR NOT (NEW.started_at <=> OLD.started_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout session source fields are immutable';
    END IF;
END$$

CREATE TRIGGER trg_workout_snapshot_fact_immutable
BEFORE UPDATE ON workout_exercise_snapshot
FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id)
            OR NOT (NEW.session_id <=> OLD.session_id)
            OR NOT (NEW.source_plan_exercise_id <=> OLD.source_plan_exercise_id)
            OR NOT (NEW.source_training_day_id <=> OLD.source_training_day_id)
            OR NOT (NEW.source_plan_version_id <=> OLD.source_plan_version_id)
            OR NOT (NEW.exercise_order <=> OLD.exercise_order)
            OR NOT (NEW.exercise_snapshot_json <=> OLD.exercise_snapshot_json)
            OR NOT (NEW.prescription_snapshot_json <=> OLD.prescription_snapshot_json) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout exercise snapshot facts are immutable';
    END IF;
END$$

CREATE TRIGGER trg_workout_set_fact_immutable
BEFORE UPDATE ON workout_set
FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id)
            OR NOT (NEW.session_exercise_id <=> OLD.session_exercise_id)
            OR NOT (NEW.client_set_key <=> OLD.client_set_key)
            OR NOT (NEW.set_type <=> OLD.set_type)
            OR NOT (NEW.set_order <=> OLD.set_order)
            OR NOT (NEW.target_json <=> OLD.target_json)
            OR NOT (NEW.unit <=> OLD.unit) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout set facts are immutable';
    END IF;
END$$

CREATE TRIGGER trg_workout_session_history_immutable_delete
BEFORE DELETE ON workout_session
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout session history is immutable';
END$$

CREATE TRIGGER trg_workout_snapshot_history_immutable_delete
BEFORE DELETE ON workout_exercise_snapshot
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout exercise snapshot history is immutable';
END$$

CREATE TRIGGER trg_workout_set_history_immutable_delete
BEFORE DELETE ON workout_set
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout set history is immutable';
END$$

CREATE TRIGGER trg_workout_set_revision_immutable_update
BEFORE UPDATE ON workout_set_revision
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout set revisions are immutable';
END$$

CREATE TRIGGER trg_workout_set_revision_immutable_delete
BEFORE DELETE ON workout_set_revision
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workout set revisions are immutable';
END$$
DELIMITER ;
