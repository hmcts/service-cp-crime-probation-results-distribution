-- V1's processed_request_completion_reason_chk and processed_request_failure_reason_chk compare
-- a NULL reason with IN (...), which evaluates UNKNOWN rather than FALSE, so a CHECK made only of
-- OR'd UNKNOWN/TRUE terms is satisfied: a COMPLETED row with a NULL completion_reason, or a FAILED
-- row with a NULL failure_reason, was silently accepted (review of PR #8; design Appendix B.2).
-- V1 is applied and checksummed, so the fix is a new migration rather than an edit to it.
ALTER TABLE processed_request DROP CONSTRAINT processed_request_completion_reason_chk;
ALTER TABLE processed_request ADD CONSTRAINT processed_request_completion_reason_chk
    CHECK ((status <> 'COMPLETED'
            OR (completion_reason IS NOT NULL
                AND completion_reason IN ('both-legs-terminal', 'recovered')))
           AND (status = 'COMPLETED' OR completion_reason IS NULL));

ALTER TABLE processed_request DROP CONSTRAINT processed_request_failure_reason_chk;
ALTER TABLE processed_request ADD CONSTRAINT processed_request_failure_reason_chk
    CHECK ((status <> 'FAILED'
            OR (failure_reason IS NOT NULL
                AND failure_reason IN ('leg-exhausted', 'rolled-back')))
           AND (status = 'FAILED' OR failure_reason IS NULL));
