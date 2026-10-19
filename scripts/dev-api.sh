#!/usr/bin/env bash
# Runs the Spring Boot API from source with settings from .env.
# Requires the database (and optionally Mailpit) from: docker compose up -d db mailpit
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  set -a; source .env; set +a
fi
# Inside docker compose the mail catcher is "mailpit"; from the host it is localhost.
if [ "${SMTP_HOST:-}" = "mailpit" ]; then export SMTP_HOST=localhost; fi
cd backend
exec mvn -q -B spring-boot:run "$@"
