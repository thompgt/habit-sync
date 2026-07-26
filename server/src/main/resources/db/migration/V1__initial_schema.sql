-- habit-sync initial schema.
--
-- Two things worth understanding before reading:
--
-- 1. Entity state is stored GENERICALLY (entity + entity_field), not as typed
--    habit/exercise tables. That mirrors sync-core's EntityRecord exactly, so the
--    server needs no per-entity-type mapping code and adding a new entity type
--    requires no server change at all. The server's job is to be a correct,
--    conflict-resolving relay -- not to understand what a habit is.
--
-- 2. change_log is the ONLY source of a server_seq, and user_sync_counter is the
--    only way to allocate one. See ADR-002: any second write path reintroduces the
--    watermark data-loss bug.

CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness without requiring the citext extension, which is not
-- available on every managed Postgres.
CREATE UNIQUE INDEX ux_app_user_email_lower ON app_user (lower(email));

-- A device is a sync replica. Its id doubles as the HLC nodeId, so it must be stable
-- for the lifetime of an installation: a device that forgets its id and takes a new
-- one loses the causal continuity of everything it wrote under the old one.
--
-- UUID text form contains no ':', which Hlc's compact encoding forbids in a nodeId.
CREATE TABLE device (
    id             UUID PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    display_name   TEXT        NOT NULL,
    -- Highest server_seq this device is known to have durably applied. Drives the
    -- tombstone-GC horizon check in ADR-003: GC must never outrun this.
    last_seen_seq  BIGINT      NOT NULL DEFAULT 0,
    last_seen_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_device_user ON device (user_id);

-- Refresh tokens are stored hashed, never in plaintext: a database leak must not hand
-- over live sessions. Rotation is enforced by `replaced_by` -- presenting a token that
-- has already been rotated is evidence of theft, and revokes the whole chain.
CREATE TABLE refresh_token (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    device_id   UUID        REFERENCES device (id) ON DELETE CASCADE,
    token_hash  TEXT        NOT NULL UNIQUE,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID        REFERENCES refresh_token (id)
);

CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);

-- ---------------------------------------------------------------------------
-- Sync infrastructure
-- ---------------------------------------------------------------------------

-- Per-user sequence allocator. Taken FOR UPDATE inside the push transaction so that
-- sequence order equals commit order for a given user. See ADR-002 -- a plain
-- BIGSERIAL loses writes here, silently and rarely.
CREATE TABLE user_sync_counter (
    user_id  UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    next_seq BIGINT NOT NULL DEFAULT 1
);

-- Merged entity state: the lifecycle register from sync-core's EntityRecord.
-- Field registers live in entity_field. The two are deliberately independent --
-- see ADR-003's correction note on why coupling them breaks commutativity.
CREATE TABLE entity (
    user_id        UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    entity_type    TEXT        NOT NULL,
    entity_id      UUID        NOT NULL,
    deleted        BOOLEAN     NOT NULL DEFAULT FALSE,
    -- HLC of the last DELETE/RESTORE, in compact 'physical:logical:node' form.
    -- NULL means the entity has never been either.
    lifecycle_hlc  TEXT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, entity_type, entity_id),
    CONSTRAINT ck_entity_deleted_has_hlc CHECK (NOT deleted OR lifecycle_hlc IS NOT NULL)
);

-- One row per field per entity: the per-field LWW registers from ADR-001.
--
-- `value` being NULL is meaningful -- it is an explicitly cleared field, distinct from
-- a field that was never written (which has no row at all). sync-core models the same
-- distinction with FieldValue.NULL versus an absent map key.
CREATE TABLE entity_field (
    user_id     UUID NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id   UUID NOT NULL,
    field       TEXT NOT NULL,
    value       TEXT,
    hlc         TEXT NOT NULL,
    PRIMARY KEY (user_id, entity_type, entity_id, field),
    FOREIGN KEY (user_id, entity_type, entity_id)
        REFERENCES entity (user_id, entity_type, entity_id) ON DELETE CASCADE
);

-- The replication log. Clients pull `WHERE user_id = ? AND server_seq > ?`, which the
-- primary key serves directly.
CREATE TABLE change_log (
    user_id     UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    server_seq  BIGINT      NOT NULL,
    op_id       UUID        NOT NULL,
    entity_type TEXT        NOT NULL,
    entity_id   UUID        NOT NULL,
    kind        TEXT        NOT NULL,
    hlc         TEXT        NOT NULL,
    -- Field writes for UPSERT; an empty object for DELETE/RESTORE.
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    origin_node TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, server_seq)
);

-- Idempotency: a push that times out after the server committed is safe to replay,
-- because this constraint recognises the op and the server treats it as a no-op
-- instead of double-applying. This replaces a separate applied_op table -- the change
-- log already records exactly which ops have been accepted.
CREATE UNIQUE INDEX ux_change_log_op ON change_log (user_id, op_id);

-- Supports the tombstone GC job in ADR-003.
CREATE INDEX ix_change_log_created_at ON change_log (created_at);
