CREATE TABLE user_account (
    id BINARY(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_user_account_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))
) ENGINE = InnoDB;

CREATE TABLE user_identity (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    subject_cipher VARBINARY(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_user_identity_provider_subject UNIQUE (provider, subject_cipher),
    KEY idx_user_identity_user_status (user_id, status),
    CONSTRAINT fk_user_identity_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_identity_provider CHECK (provider = 'WECHAT_MINI_PROGRAM')
) ENGINE = InnoDB;

CREATE TABLE user_profile (
    user_id BINARY(16) NOT NULL,
    experience VARCHAR(32) NULL,
    goal VARCHAR(64) NULL,
    weekly_frequency TINYINT UNSIGNED NULL,
    session_minutes SMALLINT UNSIGNED NULL,
    location VARCHAR(64) NULL,
    demographics_json JSON NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_profile_frequency CHECK (weekly_frequency IS NULL OR weekly_frequency BETWEEN 2 AND 6)
) ENGINE = InnoDB;

CREATE TABLE user_equipment (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    equipment_type VARCHAR(64) NOT NULL,
    min_increment DECIMAL(8,3) NOT NULL,
    unit VARCHAR(8) NOT NULL DEFAULT 'KG',
    available_levels_json JSON NULL,
    PRIMARY KEY (id),
    KEY idx_user_equipment_user_type (user_id, equipment_type),
    CONSTRAINT fk_user_equipment_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_equipment_increment CHECK (min_increment > 0),
    CONSTRAINT ck_user_equipment_unit CHECK (unit = 'KG')
) ENGINE = InnoDB;

CREATE TABLE user_exercise_preference (
    user_id BINARY(16) NOT NULL,
    exercise_id BINARY(16) NOT NULL,
    preference_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, exercise_id, preference_type),
    KEY idx_user_exercise_preference_user_type (user_id, preference_type),
    CONSTRAINT fk_user_exercise_preference_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT
) ENGINE = InnoDB;
