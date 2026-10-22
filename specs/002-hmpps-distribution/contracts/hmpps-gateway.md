# Outbound HMPPS gateway exchange

**Direction:** this service → the existing APIM HMPPS route at `distribution.hmpps.url` (`HMPPS_PUBLISH_URL`). It sends one HTTPS `POST` per attempt, with `Content-Type: application/json` and `Ocp-Apim-Subscription-Key` from configuration. The gateway handles its own HMPPS hop. The service supplies no client certificate; the platform truststore contains the gateway's internal CA. The HTTP client must not follow redirects.

The request body is the slice 001 HMPPS redaction output. There is no outbound field-level schema or validation gate in this release. `hearing.id` must survive because the gateway derives its backend path from it. Serialize the redacted tree once: the same UTF-8 bytes are POSTed, SHA-256 digested, and stored as `outbound_body`. The response body is not stored. `gateway_request_id` remains null unless an identified response header supplies it.

## Response and transport outcomes on a queue run

| Gateway response or transport result | In-run handling | Recorded leg outcome |
|---|---|---|
| Any 2xx | No retry; record the actual status. A non-200 response is a bounded warning and non-200 success count. | `SENT` |
| 4xx except 408 and 429 | No retry. | `FAILED(receiver-rejected)`; terminal |
| 5xx or 408 | No retry because acceptance by HMPPS is unknown. | `PARKED(gateway-transient)`, or `FAILED(gateway-transient)` on the final permitted delivery |
| 3xx or any other status outside 2xx/4xx/5xx | No redirect and no in-run retry. Return publisher outcome `TRANSIENT_EXHAUSTED` with the actual status. | `PARKED(gateway-transient)`, or `FAILED(gateway-transient)` on the final permitted delivery; retain the status and use `http="other"` in the leg-outcome metric |
| Read timeout after writing, or I/O failure after writing began | No retry because acceptance is unknown. | `PARKED(gateway-transient)`, or final-delivery `FAILED(gateway-transient)`; `http_status` is null if no answer arrived |
| Connection failure before the request was sent, including connect timeout; or 429 | Retry within the run under the limits below. | If still failing, `PARKED(gateway-transient)`, or final-delivery `FAILED(gateway-transient)` |

The client uses a five-second connect timeout and a 60-second read timeout. Only a never-sent connection failure and 429 may be retried in-run: at most three attempts, backoff starting at 500 ms and doubling up to 10 seconds. A delta-seconds `Retry-After` is honoured up to that cap. Start a retry only when its wait plus connect and read timeouts fit the remaining 130-second send stage. The publisher returns `SENT(status)`, `FAILED_NON_TRANSIENT(status)`, or `TRANSIENT_EXHAUSTED` with the bytes and last response status; the runner maps that to the recorded leg outcome.

`SENT` establishes only that the gateway answered 2xx. The HMPPS POST is not idempotent: a broker redelivery after an uncertain answer can send a duplicate. A final `FAILED(gateway-transient)` caused by an uncertain answer requires HMPPS confirmation before a manual resend.

**Sources:** `spec.md` FR-002-032–039 and FR-002-059; `doc/API_CONTRACTS.md` §2; `doc/design/DIGEST.md` §9. The 3xx rule follows the spec's 2026-10-21 clarification and is tagged as such in both local summaries.
