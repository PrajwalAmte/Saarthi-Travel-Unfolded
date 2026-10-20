#!/usr/bin/env bash
# Queues a Wikivoyage guide import for every destination in scripts/data/launch-destinations.tsv that
# exists in the index, has a Wikivoyage article and has no guide yet. Each guide import also queues the
# destination's climate and photo. Queues into the database that DATABASE_URL, DATABASE_USERNAME and
# DATABASE_PASSWORD (from .env) point at; an API with INGESTION_ENABLED=true connected to the same
# database then processes the queue one job at a time.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
read -r HOST PORT DB < <(python3 - <<'PY'
import os, re
m = re.match(r"jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)", os.environ["DATABASE_URL"])
print(m.group(1), m.group(2) or 5432, m.group(3))
PY
)
SSL=prefer; case "$DATABASE_URL" in *sslmode=*) SSL="$(echo "$DATABASE_URL" | sed -E 's/.*sslmode=([a-z-]+).*/\1/')";; esac
NETWORK=""; [ "$HOST" = "localhost" ] && { HOST=host.docker.internal; }
psql_run() { docker run --rm -i $NETWORK -e PGPASSWORD="$DATABASE_PASSWORD" -e PGSSLMODE="$SSL" imresamu/postgis:17-3.5 \
  psql -h "$HOST" -p "$PORT" -U "$DATABASE_USERNAME" -d "$DB" -v ON_ERROR_STOP=1 -At "$@"; }
{
  echo "create temp table want(state text, name text);"
  echo "copy want from stdin;"
  grep -v '^#' scripts/data/launch-destinations.tsv
  echo '\.'
  cat <<'SQL'
with picked as (
  select distinct p.id from want w
  join lateral (
    select p.* from place p join place_relation r on r.child_id = p.id and r.relation = 'in_state'
    join place s on s.id = r.parent_id
    where s.name = w.state and p.name = w.name and p.is_destination and p.lifecycle_status = 'active'
    order by p.sitelink_count desc nulls last limit 1) p on true
  where p.wikivoyage_title is not null
    and not exists (select 1 from guide_entry e where e.destination_id = p.id))
insert into ingestion_job (job_type, source_id, target_place_id, params, dedup_key, priority, created_by)
select 'wikivoyage_guide', 'wikivoyage-en', id, '{}'::jsonb, 'wikivoyage_guide:' || id, 20, 'admin' from picked
on conflict do nothing
returning 'queued';
SQL
} | psql_run
