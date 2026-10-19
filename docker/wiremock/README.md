# WireMock stubs for the local loop

LOCAL ONLY. The mappings in `mappings/` stand in for the three HTTP dependencies the service calls,
so `docker compose up` can run without them:

| Dependency | Path the service calls |
| --- | --- |
| results query API (payload fallback) | `GET /results-query-api/query/api/rest/results/hearingDetails/{hearingId}?hearingDate={hearingDay}` |
| users-groups permission read (youth switch) | `GET /usersgroups-query-api/query/api/rest/usersgroups/permissions?key=EMYouthFilter` |
| APIM gateway, HMPPS route | `POST /probation/api/v1/hearing/results` |

They are stubs, never a specification: the feature contracts under `specs/` are the authority, and
each mapping cites the one it mirrors. Mappings are added with the feature that calls the
dependency.

The whole directory is mounted at `/home/wiremock`, so WireMock loads `mappings/` on start. The
container is reachable on `http://localhost:8089` from the host and `http://wiremock:8080` from
inside the compose network.
