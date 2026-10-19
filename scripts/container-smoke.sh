#!/usr/bin/env bash
#
# Container smoke: build the image, run it against the committed compose dependencies and require it
# to report readiness inside the 60-second budget. Tears the stack down on every exit path, success
# or failure.
#
# This is the local equivalent of the "Container smoke" step in
# .github/workflows/ci-build-publish.yml; both run this same script, so the two cannot drift.
#
#   ./scripts/container-smoke.sh
#
# It proves the packaged artefact starts and answers, which no JUnit suite can: the *IT suites run
# inside the build's JVM and would still pass if the image were unbuildable. When the operations
# API lands, a read-only call through the same readiness gate belongs here too.

set -euo pipefail

readonly READINESS_BUDGET_SECONDS=60
readonly DEPENDENCY_BUDGET_SECONDS=120
readonly READINESS_URL="http://localhost:8082/actuator/health/readiness"

# A project name of this script's own. Everything it creates — containers, network, volumes — is
# namespaced under it, so the teardown's `down --volumes` can only ever destroy what this script
# made. Without it the script would share the default project with a developer's own
# `docker compose up`, and a smoke run would silently delete their database volume.
readonly PROJECT_NAME="probation-results-distribution-smoke"

cd "$(dirname "${BASH_SOURCE[0]}")/.."

compose() {
  docker compose --project-name "$PROJECT_NAME" "$@"
}

log() {
  printf '[container-smoke] %s\n' "$1"
}

teardown() {
  # Captured first: everything below overwrites $?, and the script's real outcome must survive the
  # cleanup rather than be replaced by it.
  local status=$?

  log "tearing down"
  if ! compose logs --no-color --tail 50 app; then
    log "WARNING: could not read the application container's logs"
  fi

  if ! compose down --volumes --remove-orphans; then
    log "FAIL: teardown left containers, networks or volumes behind"
    # A cleanup failure fails an otherwise green run: leftovers from this project poison the next
    # run, and a green tick over a stack that would not come down is a lie.
    if [ "$status" -eq 0 ]; then
      status=1
    fi
  fi

  exit "$status"
}
trap teardown EXIT

# Cleared first, then built. `bootJar` does not remove what it did not write, and the artefact is
# named for the version it was built under (`ARTEFACT_VERSION`), so a jar from an earlier build with
# a different version sits beside the new one - the Dockerfile copies `build/libs/*.jar` whole and
# the entrypoint takes the lexicographically first of them, which is how this script comes to smoke
# code that is no longer in the tree while printing PASS.
log "clearing any earlier application jar"
rm -f build/libs/*.jar

log "building the application jar"
./gradlew bootJar

log "starting dependencies"
compose up --detach postgres servicebus-emulator redis wiremock

# Only `postgres` is waited on: it is the readiness input, so the pod cannot report UP without it.
# The broker is never a readiness input, so waiting on it would only make this script slower than
# the thing it is testing.
for dependency in postgres; do
  log "waiting for ${dependency} to report healthy (budget ${DEPENDENCY_BUDGET_SECONDS}s)"
  deadline=$((SECONDS + DEPENDENCY_BUDGET_SECONDS))
  until [ "$(docker inspect --format '{{.State.Health.Status}}' \
      "$(compose ps --quiet "$dependency")")" = "healthy" ]; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      log "FAIL: ${dependency} did not become healthy within ${DEPENDENCY_BUDGET_SECONDS}s"
      exit 1
    fi
    sleep 2
  done
done

log "building the application image"
compose build app

log "starting the application container"
compose up --detach app

log "polling ${READINESS_URL} (budget ${READINESS_BUDGET_SECONDS}s)"
deadline=$((SECONDS + READINESS_BUDGET_SECONDS))
until curl --silent --fail --max-time 2 "$READINESS_URL" | grep -q '"status":"UP"'; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    log "FAIL: readiness did not report UP within ${READINESS_BUDGET_SECONDS}s"
    exit 1
  fi
  sleep 2
done

log "PASS: readiness reported UP within the ${READINESS_BUDGET_SECONDS}s budget"
