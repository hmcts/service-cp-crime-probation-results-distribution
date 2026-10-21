# Consumed payload and permission reads

These are interfaces this service consumes; it does not own their upstream contracts. The results query and users-groups calls use configured base URLs. Their request and response behavior here records what slice 002 needs from them.

## Redis `EXT_` cache

Read `EXT_{hearingId}_{hearingDay}_result_` first, then `EXT_{hearingId}_result_`. Take the first usable payload. The cache payload form is `{hearing, cpsProsecutorIds, policeCases, sharedTime}`; the HMPPS redactor decides which fields survive. Use Lettuce with reconnection, TLS and peer verification outside the `local` profile, and five-second connect and command timeouts. A missing key, unreachable cache, timeout, blank value, JSON `null`, or unparseable value is a miss for that key. Failure of both reads leads to the results query fallback; it does not stop startup or intake.

## Results query fallback

`GET {results base}/results-query-api/query/api/rest/results/hearingDetails/{hearingId}?hearingDate={hearingDay}` with `Accept: application/vnd.results.hearing-information-details+json`. `CJSCPPUID` is the command's `userId` when present, otherwise the service system user. The fallback payload form is `{hearing, sharedTime}`; it must not be expanded to the cache form.

The client has five-second connect and 30-second read timeouts, up to four attempts, and backoff from 500 ms doubling to 20 seconds. Retry 408, 429, 5xx, connect failure, and read failure, observing a delta-seconds `Retry-After` up to the backoff cap. Retries also require room in the 60-second payload stage. An exhausted non-2xx response, or a 2xx with an empty body, `{}`, or non-JSON, is a miss. If Redis and the query all miss, record `PARKED(payload-unavailable)` or final-delivery `FAILED(payload-unavailable)` without reading the youth switch.

## Users-groups youth permission

After a payload is found, once per message, call `GET {users-groups base}/usersgroups-query-api/query/api/rest/usersgroups/permissions?key={distribution.usersgroups.youth-filter-key}`. The key defaults to `EMYouthFilter`. Send `Accept: application/vnd.usersgroups.get-permissions-by-feature-key+json` and the service system user as `CJSCPPUID`.

Only `permissions[0]` decides: a truthy `active` is `ACTIVE`; a non-null first entry with falsy or absent `active`, including a non-object, is `INACTIVE`. Otherwise the reading is `UNREADABLE`, and the configured `ACTIVE` or `INACTIVE` default decides the redaction. A 2xx with a missing or empty permissions list, **or with a null first entry**, is `UNREADABLE(EMPTY_PERMISSIONS)`. A body that cannot parse is `UNREADABLE(UNPARSEABLE)`.

The client has two-second connect and five-second read timeouts, at most three attempts, and a fixed one-second wait. Retry only a no-response error or status above 429; do not honour `Retry-After`. An unusable 2xx is not retried. The 25-second switch-stage deadline can end a read as `UNREADABLE(TIMEOUT)`. A non-2xx response after the retry rule ends is `UNREADABLE(HTTP_FAILURE)`, unless the last attempt timed out. An unreadable switch does not itself park or fail the leg.

**Sources:** `spec.md` FR-002-015–025; `doc/design/DIGEST.md` §10; `doc/API_CONTRACTS.md` §§1–2. The null-first-permission cause follows the spec's 2026-10-21 clarification.
