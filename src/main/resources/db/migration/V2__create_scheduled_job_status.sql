-- V2 scheduled-job status (H38, A3-118; amended 2026-10-24 from the one-row body_maintenance_state):
-- one row per scheduled job, keyed by job_name, recording when it last completed a run with no
-- failure. Read independently of any run, on every pod, so a job that never runs is noticed without
-- waiting for the create-ahead buffer to exhaust (open question 2). V1 is unchanged.
CREATE TABLE scheduled_job_status (
    job_name        text        NOT NULL,
    last_success_at timestamptz NOT NULL,
    CONSTRAINT scheduled_job_status_pk PRIMARY KEY (job_name)
);

INSERT INTO scheduled_job_status (job_name, last_success_at)
VALUES ('body-retention', clock_timestamp());
