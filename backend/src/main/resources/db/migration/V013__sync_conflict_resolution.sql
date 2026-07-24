ALTER TABLE sync_conflict
    ADD COLUMN resolution VARCHAR(32) NULL AFTER status,
    ADD COLUMN sync_version BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER resolution;

ALTER TABLE sync_conflict
    ADD CONSTRAINT ck_sync_conflict_resolution_state CHECK (
        (status = 'OPEN' AND resolution IS NULL AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolution IS NOT NULL AND resolved_at IS NOT NULL)
    );
