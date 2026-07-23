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
    exercise_id BINARY(16) NOT NULL,
    exercise_order SMALLINT UNSIGNED NOT NULL,
    prescription_json JSON NOT NULL,
    weight_status VARCHAR(32) NOT NULL,
    progression_rule_code VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_plan_exercise_order UNIQUE (training_day_id, exercise_order),
    KEY idx_plan_exercise_exercise (exercise_id),
    CONSTRAINT fk_plan_exercise_training_day FOREIGN KEY (training_day_id) REFERENCES training_day (id) ON DELETE RESTRICT,
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
CREATE TRIGGER trg_training_plan_version_immutable_update
BEFORE UPDATE ON training_plan_version
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training_plan_version rows are immutable';
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER trg_training_plan_version_immutable_delete
BEFORE DELETE ON training_plan_version
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'training_plan_version rows are immutable';
END$$
DELIMITER ;
