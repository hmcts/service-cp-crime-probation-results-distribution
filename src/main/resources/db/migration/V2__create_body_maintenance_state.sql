-- V2 body-maintenance state (H38, A3-118): a one-row table recording when the body-retention job
-- last completed a run with no failure. Read independently of any run, on every pod, so a job that
-- never runs is noticed without waiting for the create-ahead buffer to exhaust (open question 2).
-- V1 is unchanged.
CREATE TABLE body_maintenance_state (
    last_success_at timestamptz NOT NULL
);

INSERT INTO body_maintenance_state (last_success_at) VALUES (clock_timestamp());
