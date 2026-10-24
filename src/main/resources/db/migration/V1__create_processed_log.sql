-- V1 processed log (design Appendix B.2, digest §8): three tables and one view.
-- The database clock is the only time authority. The informant register's processed_request block
-- is reproduced, then this service's changes are applied as ALTERs. outbound_message carries the
-- full §4.6 CHECK matrix, including cells only slice 004 writes. outbound_body is month-partitioned
-- with a DEFAULT partition and a digest-checking trigger (H20, H35).

-- ---------------------------------------------------------------------------
-- processed_request — the informant register block
-- ---------------------------------------------------------------------------
CREATE TABLE processed_request (
    source                text        NOT NULL,
    request_id            uuid        NOT NULL,
    hearing_id            uuid        NOT NULL,
    hearing_day           date        NOT NULL,
    shared_time           timestamptz NOT NULL,
    event_type            text        NOT NULL,
    request_fingerprint   text        NOT NULL,
    status                text        NOT NULL,
    attempts              integer     NOT NULL DEFAULT 0,
    completion_reason     text,
    failure_reason        text,
    exhausted_message_id  text,
    audit_note            text,
    claim_owner           text,
    claim_token           uuid,
    claim_expires_at      timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT processed_request_pk PRIMARY KEY (source, request_id),
    CONSTRAINT processed_request_status_chk
        CHECK (status IN ('RECEIVED', 'RETRYING', 'COMPLETED', 'FAILED')),
    CONSTRAINT processed_request_attempts_chk CHECK (attempts >= 0),
    CONSTRAINT processed_request_claim_triple_chk
        CHECK (num_nonnulls(claim_owner, claim_token, claim_expires_at) IN (0, 3))
);

CREATE INDEX processed_request_hearing_idx ON processed_request (hearing_id, hearing_day);

-- This service's changes, applied as ALTERs (design Appendix B.2).
ALTER TABLE processed_request ADD COLUMN command text NOT NULL;
ALTER TABLE processed_request ADD COLUMN replayed_at timestamptz;

ALTER TABLE processed_request ADD CONSTRAINT processed_request_exhausted_id_chk
    CHECK (status <> 'FAILED'
           OR failure_reason IS DISTINCT FROM 'leg-exhausted'
           OR exhausted_message_id IS NOT NULL);

ALTER TABLE processed_request ADD CONSTRAINT processed_request_completion_reason_chk
    CHECK ((status <> 'COMPLETED' OR completion_reason IN ('both-legs-terminal', 'recovered'))
           AND (completion_reason IS NULL
                OR completion_reason IN ('both-legs-terminal', 'recovered')));

ALTER TABLE processed_request ADD CONSTRAINT processed_request_failure_reason_chk
    CHECK ((status <> 'FAILED' OR failure_reason IN ('leg-exhausted', 'rolled-back'))
           AND (status = 'FAILED' OR failure_reason IS NULL));

-- ---------------------------------------------------------------------------
-- outbound_message — one row per leg per attempt
-- ---------------------------------------------------------------------------
CREATE TABLE outbound_message (
    id                   uuid        NOT NULL,
    source               text        NOT NULL,
    request_id           uuid        NOT NULL,
    leg                  text        NOT NULL,
    attempt              integer     NOT NULL DEFAULT 1,
    attempt_origin       text        NOT NULL DEFAULT 'queue',
    status               text        NOT NULL,
    reason               text,
    attempts             integer     NOT NULL DEFAULT 0,
    http_status          integer,
    gateway_request_id   text,
    body_digest          text,
    payload_source       text,
    payload_shared_time  timestamptz,
    service_version      text,
    youth_switch         text,
    claim_owner          text,
    claim_token          uuid,
    claim_expires_at     timestamptz,
    resend_run_id        uuid,
    operator             text,
    override             boolean     NOT NULL DEFAULT false,
    note                 varchar(256),
    sent_at              timestamptz,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT outbound_message_pk PRIMARY KEY (id),
    CONSTRAINT outbound_message_attempt_uk UNIQUE (source, request_id, leg, attempt),
    CONSTRAINT outbound_message_request_fk FOREIGN KEY (source, request_id)
        REFERENCES processed_request (source, request_id) ON DELETE RESTRICT,
    CONSTRAINT outbound_message_leg_chk CHECK (leg IN ('HMPPS')),
    CONSTRAINT outbound_message_leg_reason_chk
        CHECK (reason IS DISTINCT FROM 'nothing-left-after-redaction' OR leg = 'HMPPS'),
    CONSTRAINT outbound_message_attempt_chk CHECK (attempt >= 1),
    CONSTRAINT outbound_message_attempts_chk CHECK (attempts >= 0),
    CONSTRAINT outbound_message_attempt_origin_chk
        CHECK (attempt_origin IN ('queue', 'resend', 'replay')),
    CONSTRAINT outbound_message_attempt_origin_attempt_chk
        CHECK ((attempt = 1) = (attempt_origin = 'queue')),
    CONSTRAINT outbound_message_status_chk
        CHECK (status IN ('PENDING', 'SENT', 'NOT_APPLICABLE', 'PARKED', 'FAILED')),
    CONSTRAINT outbound_message_status_reason_chk CHECK (
        (status = 'PENDING' AND reason IS NULL)
        OR (status = 'SENT' AND reason IS NULL)
        OR (status = 'NOT_APPLICABLE'
            AND coalesce(reason, '') IN ('sjp-or-box-hearing', 'nothing-left-after-redaction'))
        OR (status = 'PARKED'
            AND coalesce(reason, '') IN ('payload-unavailable', 'gateway-transient'))
        OR (status = 'FAILED'
            AND coalesce(reason, '') IN ('receiver-rejected', 'rules-exception', 'contract-invalid',
                                         'payload-unavailable', 'gateway-transient',
                                         'payload-changed', 'rolled-back'))
    ),
    CONSTRAINT outbound_message_youth_switch_chk
        CHECK (youth_switch IS NULL OR youth_switch IN ('ACTIVE', 'INACTIVE',
               'UNREADABLE_DEFAULTED_ACTIVE', 'UNREADABLE_DEFAULTED_INACTIVE')),
    CONSTRAINT outbound_message_payload_source_chk
        CHECK (payload_source IS NULL
               OR payload_source IN ('cache-day', 'cache-nullday', 'query')),
    CONSTRAINT outbound_message_sent_at_chk CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT outbound_message_sent_evidence_chk
        CHECK (status <> 'SENT' OR http_status IS NOT NULL),
    CONSTRAINT outbound_message_body_digest_chk
        CHECK (status <> 'SENT' OR body_digest IS NOT NULL),
    CONSTRAINT outbound_message_claim_triple_chk
        CHECK (num_nonnulls(claim_owner, claim_token, claim_expires_at) IN (0, 3)),
    CONSTRAINT outbound_message_claim_attempt_chk
        CHECK ((attempt_origin = 'resend') = (claim_owner IS NOT NULL)),
    CONSTRAINT outbound_message_resend_run_chk
        CHECK ((attempt_origin = 'resend') = (resend_run_id IS NOT NULL)),
    CONSTRAINT outbound_message_note_chk
        CHECK (note IS NULL OR char_length(note) BETWEEN 1 AND 256),
    CONSTRAINT outbound_message_presence_payload_chk
        CHECK (payload_source IS NOT NULL
               OR status = 'PENDING'
               OR coalesce(reason, '') IN ('payload-unavailable', 'rolled-back')),
    CONSTRAINT outbound_message_presence_body_digest_chk
        CHECK (coalesce(reason, '') = 'rolled-back'
               OR ((body_digest IS NOT NULL)
                   = (status = 'SENT'
                      OR coalesce(reason, '') IN ('receiver-rejected', 'gateway-transient')))),
    CONSTRAINT outbound_message_presence_service_version_chk
        CHECK ((service_version IS NOT NULL) = (body_digest IS NOT NULL)),
    CONSTRAINT outbound_message_presence_http_status_chk
        CHECK (NOT (status = 'SENT' OR coalesce(reason, '') = 'receiver-rejected')
               OR http_status IS NOT NULL),
    CONSTRAINT outbound_message_presence_youth_switch_chk
        CHECK (coalesce(reason, '') = 'rolled-back'
               OR ((youth_switch IS NOT NULL)
                   = NOT (status = 'PENDING'
                          OR coalesce(reason, '') IN ('payload-unavailable', 'payload-changed')))),
    CONSTRAINT outbound_message_pairing_shared_time_chk
        CHECK (payload_shared_time IS NULL OR payload_source IS NOT NULL),
    CONSTRAINT outbound_message_pairing_digest_source_chk
        CHECK (body_digest IS NULL OR payload_source IS NOT NULL),
    CONSTRAINT outbound_message_pairing_youth_source_chk
        CHECK (youth_switch IS NULL OR payload_source IS NOT NULL),
    CONSTRAINT outbound_message_pairing_http_digest_chk
        CHECK (http_status IS NULL OR body_digest IS NOT NULL)
);

CREATE INDEX outbound_message_status_idx ON outbound_message (leg, status, updated_at);

-- ---------------------------------------------------------------------------
-- outbound_message_latest — the highest attempt per leg is a leg's truth
-- ---------------------------------------------------------------------------
CREATE VIEW outbound_message_latest AS
SELECT DISTINCT ON (source, request_id, leg) *
FROM outbound_message
ORDER BY source, request_id, leg, attempt DESC;

-- ---------------------------------------------------------------------------
-- outbound_body — the exact bytes POSTed, month-partitioned (UTC)
-- ---------------------------------------------------------------------------
CREATE TABLE outbound_body (
    outbound_message_id uuid        NOT NULL,
    body                text        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT outbound_body_pk PRIMARY KEY (outbound_message_id, created_at),
    CONSTRAINT outbound_body_message_fk FOREIGN KEY (outbound_message_id)
        REFERENCES outbound_message (id) ON DELETE RESTRICT
) PARTITION BY RANGE (created_at);

CREATE FUNCTION outbound_body_digest_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    expected text;
BEGIN
    SELECT body_digest INTO expected
    FROM outbound_message
    WHERE id = NEW.outbound_message_id;
    IF NOT FOUND THEN
        RETURN NEW; -- no parent: leave it to the foreign key
    END IF;
    IF expected IS NULL
       OR expected <> encode(sha256(convert_to(NEW.body, 'UTF8')), 'hex') THEN
        RAISE EXCEPTION 'outbound_body digest mismatch for %', NEW.outbound_message_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER outbound_body_digest_trg
    BEFORE INSERT OR UPDATE ON outbound_body
    FOR EACH ROW EXECUTE FUNCTION outbound_body_digest_check();

CREATE TABLE IF NOT EXISTS outbound_body_default PARTITION OF outbound_body DEFAULT;

-- The current month's partition and the next two, each created only if absent (slice 003 maintains
-- the months after that, by the same name, bounds and rule).
DO $$
DECLARE
    base  date := date_trunc('month', (now() AT TIME ZONE 'UTC'))::date;
    i     integer;
    lo    timestamptz;
    hi    timestamptz;
    pname text;
BEGIN
    FOR i IN 0..2 LOOP
        lo := ((base + (i || ' month')::interval)::timestamp) AT TIME ZONE 'UTC';
        hi := ((base + ((i + 1) || ' month')::interval)::timestamp) AT TIME ZONE 'UTC';
        pname := 'outbound_body_' || to_char((base + (i || ' month')::interval), 'YYYY_MM');
        IF to_regclass(pname) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF outbound_body FOR VALUES FROM (%L) TO (%L)',
                pname, lo, hi);
        END IF;
    END LOOP;
END $$;
