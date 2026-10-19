#!/usr/bin/env bash
# Builds a deployment dump of the local database without local test data.
#
#   scripts/make-release-dump.sh            # uses the docker compose "db" service, database destination_intel
#
# Stop the local API first: Postgres can only copy a database nobody is connected to. The script
# copies the database, removes accounts, sessions, contributions, audit history and anything they
# changed from the copy (restoring the source facts that test corrections replaced), dumps the copy
# to deploy/, test-restores it and compares every table's row count, then drops the scratch copies.
# The local database itself is not modified.
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${DB_CONTAINER:-$(docker compose ps -q db)}"
if [ -z "$CONTAINER" ]; then
  echo "The database is not running; start it with: docker compose up -d db" >&2
  exit 1
fi
SOURCE_DB="${SOURCE_DB:-destination_intel}"
USER_NAME="${POSTGRES_USER:-idi}"
STAMP="$(date +%Y-%m-%d)"
OUT="deploy/saarthi-${STAMP}.dump"
psql_db() { docker exec -i "$CONTAINER" psql -U "$USER_NAME" -v ON_ERROR_STOP=1 -At -d "$1" "${@:2}"; }

mkdir -p deploy
if [ "$(psql_db postgres -c "select count(*) from pg_stat_activity where datname = '${SOURCE_DB}'")" != "0" ]; then
  echo "Something is connected to ${SOURCE_DB}; stop the local API and try again." >&2
  exit 1
fi

echo "Copying ${SOURCE_DB}…"
psql_db postgres -c "DROP DATABASE IF EXISTS saarthi_release" -c "CREATE DATABASE saarthi_release TEMPLATE ${SOURCE_DB}" >/dev/null

echo "Removing local accounts and test activity from the copy…"
psql_db saarthi_release <<'SQL'
BEGIN;
UPDATE fact o SET status = 'published', updated_at = now()
FROM fact c WHERE c.origin = 'community' AND c.supersedes_fact_id = o.id AND o.status = 'superseded';
UPDATE fact f SET last_verified_at = NULL WHERE f.id IN (SELECT fact_id FROM verification);
UPDATE contribution SET result_fact_id = NULL, target_fact_id = NULL;
DELETE FROM fact_observation WHERE contribution_id IS NOT NULL;
DELETE FROM verification;
DELETE FROM fact WHERE origin = 'community';
DELETE FROM guide_entry WHERE origin = 'community';
DELETE FROM moderation_item;
DELETE FROM abuse_flag;
DELETE FROM trust_event;
DELETE FROM steward_assignment;
DELETE FROM contribution;
DELETE FROM audit_log;
DELETE FROM destination_request;
UPDATE poi_match_review SET resolved_by = NULL WHERE resolved_by IS NOT NULL;
DELETE FROM user_identity;
DELETE FROM app_user;
DELETE FROM spring_session_attributes;
DELETE FROM spring_session;
DELETE FROM login_token;
COMMIT;
SQL

COUNTS="select string_agg(format('%s=%s', table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I', table_name), false, true, '')))[1]::text), E'\n' order by table_name) from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'"
expected="$(psql_db saarthi_release -c "$COUNTS")"

echo "Dumping to ${OUT}…"
docker exec "$CONTAINER" pg_dump -U "$USER_NAME" -d saarthi_release -Fc -Z 6 --no-owner --no-privileges > "$OUT"
shasum -a 256 "$OUT" > "${OUT}.sha256"
psql_db postgres -c "DROP DATABASE saarthi_release" >/dev/null

echo "Test-restoring…"
psql_db postgres -c "DROP DATABASE IF EXISTS restore_test" -c "CREATE DATABASE restore_test" >/dev/null
docker exec -i "$CONTAINER" pg_restore -U "$USER_NAME" -d restore_test --no-owner --no-privileges --exit-on-error < "$OUT"
actual="$(psql_db restore_test -c "$COUNTS")"
psql_db postgres -c "DROP DATABASE restore_test" >/dev/null
if [ "$expected" != "$actual" ]; then
  echo "Row counts differ after restore; do not use ${OUT}." >&2
  diff <(echo "$expected") <(echo "$actual") >&2 || true
  exit 1
fi
echo "Done: ${OUT} ($(du -h "$OUT" | cut -f1)), $(echo "$expected" | wc -l | tr -d ' ') tables verified by test restore."
