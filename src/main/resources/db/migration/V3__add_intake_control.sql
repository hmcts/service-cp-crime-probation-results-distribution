-- V3 intake_control (data-model.md "New entity: intake_control"; research.md Decision 3).
-- FR-004-052, confirmed: a single-row control table recording whether an operator has paused
-- intake, read by every pod on startup and on distribution.store.probe-interval, written only by
-- the pause/resume actions.

CREATE TABLE intake_control (
    id                   smallint    PRIMARY KEY DEFAULT 1,
    paused_by_operator   boolean     NOT NULL DEFAULT false,
    paused_at            timestamptz,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT intake_control_single_row_chk CHECK (id = 1)
);

INSERT INTO intake_control (id) VALUES (1);
