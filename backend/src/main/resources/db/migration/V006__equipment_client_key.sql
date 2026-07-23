ALTER TABLE user_equipment
    ADD COLUMN client_equipment_key BINARY(16) NULL AFTER user_id;

UPDATE user_equipment
SET client_equipment_key = UUID_TO_BIN(UUID())
WHERE client_equipment_key IS NULL;

ALTER TABLE user_equipment
    MODIFY COLUMN client_equipment_key BINARY(16) NOT NULL,
    ADD UNIQUE KEY uq_user_equipment_user_client_key (user_id, client_equipment_key);
