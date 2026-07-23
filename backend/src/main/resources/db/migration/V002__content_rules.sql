CREATE TABLE exercise (
    id BINARY(16) NOT NULL,
    measurement_type VARCHAR(32) NOT NULL,
    movement_pattern VARCHAR(64) NOT NULL,
    difficulty VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    content_version VARCHAR(64) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_exercise_status_review (status, review_status)
) ENGINE = InnoDB;

ALTER TABLE user_exercise_preference
    ADD CONSTRAINT fk_user_exercise_preference_exercise FOREIGN KEY (exercise_id) REFERENCES exercise (id) ON DELETE RESTRICT;

CREATE TABLE exercise_i18n (
    exercise_id BINARY(16) NOT NULL,
    locale VARCHAR(16) NOT NULL,
    name VARCHAR(255) NOT NULL,
    instructions_json JSON NOT NULL,
    safety_tips_json JSON NULL,
    PRIMARY KEY (exercise_id, locale),
    CONSTRAINT fk_exercise_i18n_exercise FOREIGN KEY (exercise_id) REFERENCES exercise (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE exercise_equipment (
    exercise_id BINARY(16) NOT NULL,
    equipment_type VARCHAR(64) NOT NULL,
    PRIMARY KEY (exercise_id, equipment_type),
    KEY idx_exercise_equipment_type (equipment_type),
    CONSTRAINT fk_exercise_equipment_exercise FOREIGN KEY (exercise_id) REFERENCES exercise (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE exercise_muscle (
    exercise_id BINARY(16) NOT NULL,
    muscle_code VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (exercise_id, muscle_code, role),
    KEY idx_exercise_muscle_role (muscle_code, role),
    CONSTRAINT fk_exercise_muscle_exercise FOREIGN KEY (exercise_id) REFERENCES exercise (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE exercise_alternative (
    exercise_id BINARY(16) NOT NULL,
    alternative_id BINARY(16) NOT NULL,
    rank_no SMALLINT UNSIGNED NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    PRIMARY KEY (exercise_id, alternative_id),
    CONSTRAINT fk_exercise_alternative_exercise FOREIGN KEY (exercise_id) REFERENCES exercise (id) ON DELETE RESTRICT,
    CONSTRAINT fk_exercise_alternative_target FOREIGN KEY (alternative_id) REFERENCES exercise (id) ON DELETE RESTRICT,
    CONSTRAINT ck_exercise_alternative_not_self CHECK (exercise_id <> alternative_id)
) ENGINE = InnoDB;

CREATE TABLE plan_template_version (
    id BINARY(16) NOT NULL,
    template_code VARCHAR(64) NOT NULL,
    version VARCHAR(64) NOT NULL,
    frequency TINYINT UNSIGNED NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_plan_template_version UNIQUE (template_code, version),
    KEY idx_plan_template_frequency_status (frequency, status),
    CONSTRAINT ck_plan_template_frequency CHECK (frequency BETWEEN 2 AND 6)
) ENGINE = InnoDB;

CREATE TABLE rule_config_version (
    id BINARY(16) NOT NULL,
    version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    config_json JSON NOT NULL,
    published_at DATETIME(6) NULL,
    active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uq_rule_config_version UNIQUE (version),
    CONSTRAINT uq_rule_config_single_active UNIQUE (active_marker)
) ENGINE = InnoDB;
