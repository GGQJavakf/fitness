CREATE TABLE training_plan (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    active_version_id BINARY(16) NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_training_plan_id_user UNIQUE (id, user_id),
    KEY idx_training_plan_user_status (user_id, status),
    CONSTRAINT fk_training_plan_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE training_plan_version (
    id BINARY(16) NOT NULL,
    plan_id BINARY(16) NOT NULL,
    version_no INT UNSIGNED NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    split_type VARCHAR(32) NOT NULL,
    frequency TINYINT UNSIGNED NOT NULL,
    template_version VARCHAR(64) NULL,
    rule_version VARCHAR(64) NOT NULL,
    change_summary_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    sealed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_training_plan_version_number UNIQUE (plan_id, version_no),
    CONSTRAINT uq_training_plan_version_id_plan UNIQUE (id, plan_id),
    KEY idx_training_plan_version_plan_created (plan_id, created_at),
    CONSTRAINT fk_training_plan_version_plan FOREIGN KEY (plan_id) REFERENCES training_plan (id) ON DELETE RESTRICT,
    CONSTRAINT ck_training_plan_version_frequency CHECK (frequency BETWEEN 2 AND 6)
) ENGINE = InnoDB;

ALTER TABLE training_plan
    ADD CONSTRAINT fk_training_plan_active_version FOREIGN KEY (active_version_id, id) REFERENCES training_plan_version (id, plan_id) ON DELETE RESTRICT;

CREATE TABLE training_day (
    id BINARY(16) NOT NULL,
    plan_version_id BINARY(16) NOT NULL,
    day_order TINYINT UNSIGNED NOT NULL,
    weekday TINYINT UNSIGNED NULL,
    name VARCHAR(128) NOT NULL,
    target_muscles_json JSON NULL,
    estimated_minutes SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_training_day_order UNIQUE (plan_version_id, day_order),
    CONSTRAINT uq_training_day_id_version UNIQUE (id, plan_version_id),
    CONSTRAINT fk_training_day_plan_version FOREIGN KEY (plan_version_id) REFERENCES training_plan_version (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE plan_exercise (
    id BINARY(16) NOT NULL,
    training_day_id BINARY(16) NOT NULL,
    plan_version_id BINARY(16) NOT NULL,
    exercise_id BINARY(16) NOT NULL,
    exercise_order SMALLINT UNSIGNED NOT NULL,
    prescription_json JSON NOT NULL,
    weight_status VARCHAR(32) NOT NULL,
    progression_rule_code VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_plan_exercise_order UNIQUE (training_day_id, exercise_order),
    CONSTRAINT uq_plan_exercise_id_day_version UNIQUE (id, training_day_id, plan_version_id),
    KEY idx_plan_exercise_exercise (exercise_id),
    CONSTRAINT fk_plan_exercise_training_day_version FOREIGN KEY (training_day_id, plan_version_id) REFERENCES training_day (id, plan_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_exercise_exercise FOREIGN KEY (exercise_id) REFERENCES exercise (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE plan_field_lock (
    plan_exercise_id BINARY(16) NOT NULL,
    field_path VARCHAR(512) NOT NULL,
    lock_status VARCHAR(32) NOT NULL,
    locked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (plan_exercise_id, field_path),
    CONSTRAINT fk_plan_field_lock_exercise FOREIGN KEY (plan_exercise_id) REFERENCES plan_exercise (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

DELIMITER $$
CREATE TRIGGER trg_training_plan_version_seal_once
BEFORE UPDATE ON training_plan_version
FOR EACH ROW
BEGIN
    IF OLD.sealed_at IS NULL
            AND NEW.sealed_at IS NOT NULL
            AND NEW.id <=> OLD.id
            AND NEW.plan_id <=> OLD.plan_id
            AND NEW.version_no <=> OLD.version_no
            AND NEW.source_type <=> OLD.source_type
            AND NEW.split_type <=> OLD.split_type
            AND NEW.frequency <=> OLD.frequency
            AND NEW.template_version <=> OLD.template_version
            AND NEW.rule_version <=> OLD.rule_version
            AND NEW.change_summary_json <=> OLD.change_summary_json
            AND NEW.created_at <=> OLD.created_at THEN
        SET NEW.sealed_at = NEW.sealed_at;
    ELSE
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version may only transition from unsealed to sealed';
    END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER trg_training_plan_version_immutable_delete
BEFORE DELETE ON training_plan_version
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version rows are immutable';
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER trg_training_plan_active_version_must_be_sealed
BEFORE UPDATE ON training_plan
FOR EACH ROW
BEGIN
    IF NOT (NEW.active_version_id <=> OLD.active_version_id)
            AND NEW.active_version_id IS NOT NULL
            AND NOT EXISTS (
                SELECT 1 FROM training_plan_version
                WHERE id = NEW.active_version_id AND plan_id = NEW.id AND sealed_at IS NOT NULL
            ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'active training plan version must be sealed';
    END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER trg_training_day_reject_write_when_sealed_insert
BEFORE INSERT ON training_day
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM training_plan_version WHERE id = NEW.plan_version_id AND sealed_at IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version is sealed';
    END IF;
END$$
CREATE TRIGGER trg_training_day_reject_write_when_sealed_update
BEFORE UPDATE ON training_day
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM training_plan_version WHERE id IN (OLD.plan_version_id, NEW.plan_version_id) AND sealed_at IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version is sealed';
    END IF;
END$$
CREATE TRIGGER trg_training_day_reject_write_when_sealed_delete
BEFORE DELETE ON training_day
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM training_plan_version WHERE id = OLD.plan_version_id AND sealed_at IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version is sealed';
    END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER trg_plan_exercise_reject_write_when_sealed_insert
BEFORE INSERT ON plan_exercise
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM training_plan_version WHERE id = NEW.plan_version_id AND sealed_at IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version is sealed';
    END IF;
END$$
CREATE TRIGGER trg_plan_exercise_reject_write_when_sealed_update
BEFORE UPDATE ON plan_exercise
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM training_plan_version WHERE id IN (OLD.plan_version_id, NEW.plan_version_id) AND sealed_at IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version is sealed';
    END IF;
END$$
CREATE TRIGGER trg_plan_exercise_reject_write_when_sealed_delete
BEFORE DELETE ON plan_exercise
FOR EACH ROW
BEGIN
    IF EXISTS (SELECT 1 FROM training_plan_version WHERE id = OLD.plan_version_id AND sealed_at IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version is sealed';
    END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER trg_plan_field_lock_reject_write_when_sealed_insert
BEFORE INSERT ON plan_field_lock
FOR EACH ROW
BEGIN
    IF EXISTS (
        SELECT 1 FROM plan_exercise pe JOIN training_plan_version pv ON pv.id = pe.plan_version_id
        WHERE pe.id = NEW.plan_exercise_id AND pv.sealed_at IS NOT NULL
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version is sealed';
    END IF;
END$$
CREATE TRIGGER trg_plan_field_lock_reject_write_when_sealed_update
BEFORE UPDATE ON plan_field_lock
FOR EACH ROW
BEGIN
    IF EXISTS (
        SELECT 1 FROM plan_exercise pe JOIN training_plan_version pv ON pv.id = pe.plan_version_id
        WHERE pe.id IN (OLD.plan_exercise_id, NEW.plan_exercise_id) AND pv.sealed_at IS NOT NULL
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version is sealed';
    END IF;
END$$
CREATE TRIGGER trg_plan_field_lock_reject_write_when_sealed_delete
BEFORE DELETE ON plan_field_lock
FOR EACH ROW
BEGIN
    IF EXISTS (
        SELECT 1 FROM plan_exercise pe JOIN training_plan_version pv ON pv.id = pe.plan_version_id
        WHERE pe.id = OLD.plan_exercise_id AND pv.sealed_at IS NOT NULL
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training plan version is sealed';
    END IF;
END$$
DELIMITER ;
