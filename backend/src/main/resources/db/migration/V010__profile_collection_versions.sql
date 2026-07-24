CREATE TABLE user_profile_collection_version (
    user_id BINARY(16) NOT NULL,
    equipment_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    preference_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profile_collection_version_user
        FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

ALTER TABLE user_equipment
    ADD COLUMN item_order SMALLINT UNSIGNED NOT NULL DEFAULT 0 AFTER client_equipment_key;

ALTER TABLE user_exercise_preference
    ADD COLUMN preference_order SMALLINT UNSIGNED NOT NULL DEFAULT 0 AFTER preference_type;

INSERT INTO user_profile_collection_version
    (user_id, equipment_version, preference_version, updated_at)
SELECT user_account.id,
       CASE WHEN EXISTS (
           SELECT 1 FROM user_equipment
           WHERE user_equipment.user_id = user_account.id
       ) THEN 1 ELSE 0 END,
       CASE WHEN EXISTS (
           SELECT 1 FROM user_exercise_preference
           WHERE user_exercise_preference.user_id = user_account.id
       ) THEN 1 ELSE 0 END,
       UTC_TIMESTAMP(6)
FROM user_account
WHERE EXISTS (
          SELECT 1 FROM user_equipment
          WHERE user_equipment.user_id = user_account.id
      )
   OR EXISTS (
          SELECT 1 FROM user_exercise_preference
          WHERE user_exercise_preference.user_id = user_account.id
      );
