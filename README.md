# Saarthi — Travel Unfolded

**One place to understand any place in India.** A free, open-source destination guide: where to eat, which
neighbourhood to stay in, what to explore, what things cost, how to get around and local tips. Every fact shows
where it came from, when it was observed and whether locals have checked it.

It is a destination knowledge platform, not a booking engine, itinerary generator or chatbot. Guides are built
ahead of time from open sources (Wikidata, Wikivoyage, Open-Meteo) and improved by contributors through a
moderated “Still true?” loop. Nothing is generated when you search, and gaps are shown as gaps.

- **Readers** search any indexed Indian place (by name, alternate spelling, partial or misspelt name, or in an
  Indian script), choose optional preferences and browse the guide without an account.
- **Contributors** sign in with an emailed link (or optional Google/GitHub OAuth) to confirm, correct and add
  facts. New contributors’ changes are moderated; trusted contributors and city stewards review them.
- **Operators** run everything with Docker Compose on free infrastructure. No paid service is required.

---

## Contents

1. [Quick start](#quick-start)
2. [What you get after the first start](#what-you-get-after-the-first-start)
3. [Local development](#local-development)
4. [Configuration](#configuration)
5. [Ingesting real data](#ingesting-real-data)
6. [Contributor sign-in and administration](#contributor-sign-in-and-administration)
7. [Testing](#testing)
8. [Architecture](#architecture)
9. [API](#api)
10. [Licences](#licences)

---

## Quick start

Requirements: Docker with Compose v2, about 4 GB of free memory and outbound internet access (ingestion calls
the Wikidata, Wikivoyage and Open-Meteo APIs).

```bash
cp .env.example .env
```

Edit `.env`: set `APP_ADMIN_EMAILS` to your email address (to get the operations console) and, ideally,
`INGESTION_CONTACT` to a URL or email where Wikimedia can reach the operator. On Apple Silicon, set
`POSTGIS_IMAGE=imresamu/postgis:17-3.5` to avoid x86 emulation. Then:

```bash
docker compose up --build
```

| Service | URL |
| --- | --- |
| Web app | http://localhost:3000 |
| Sign-in emails (Mailpit, development only) | http://localhost:8025 |
| PostgreSQL + PostGIS | `localhost:5433` (user and password from `.env`) |

The API is reached through the web app’s same-origin proxy (`/api/*`) and is not published on the host by
default. Uncomment the `ports` entry of the `api` service in `docker-compose.yml` to open
http://localhost:8080/swagger-ui.html.

## What you get after the first start

On first start the API migrates the database (Flyway) and queues real ingestion work in the background:

1. The launch destinations (`INGESTION_SEED_GUIDES`, by default Mysuru and Bengaluru) are indexed from
   Wikidata and their Wikivoyage articles ingested, including Bengaluru’s district articles.
2. The nationwide index is built from Wikidata: all states and union territories, about 800 districts, every
   Indian place with an English Wikivoyage article (about 1,800) and settlements above 100,000 people, with
   names in 13 Indian languages and administrative relations.
3. Ten years of daily weather for each guide destination are aggregated into monthly climate averages
   (Open-Meteo), and a freely licensed image with its credit is linked from Wikimedia Commons.

The first guides appear within a few minutes; the nationwide index takes longer because the public Wikidata
query service is shared and rate-limited (the client backs off and retries). Progress and failures are visible
at http://localhost:3000/sources and, for administrators, in the operations console at `/admin`.

Until data arrives the homepage says the index is empty, and search returns nothing rather than placeholder
results. Coverage labels are computed from stored data: for example, Mysuru is labelled **Needs refresh**
because most Wikivoyage listing dates are several years old. Contributors confirming facts is what moves it to
**Usable**.

## Local development

Run the infrastructure in Docker and the applications from source:

```bash
docker compose up -d db mailpit
```

```bash
./scripts/dev-api.sh
```

`scripts/dev-api.sh` loads `.env` and runs `mvn spring-boot:run` (JDK 21+ and Maven 3.9+). The API listens on
http://localhost:8080.

```bash
cd frontend && npm ci && npm run dev
```

The web app runs on http://localhost:3000 and proxies `/api`, `/oauth2` and `/login/oauth2` to
`BACKEND_INTERNAL_URL` (default `http://localhost:8080`).

Useful commands:

| Task | Command |
| --- | --- |
| Backend unit and integration tests (needs Docker) | `cd backend && mvn verify` |
| Frontend unit and accessibility tests | `cd frontend && npm test` |
| Frontend type check and lint | `cd frontend && npm run typecheck && npm run lint` |
| Frontend production build | `cd frontend && npm run build` |
| End-to-end tests | see [Testing](#testing) |

To reset the local database: `docker compose down -v` (this deletes all ingested data and accounts).

## Configuration

All configuration comes from environment variables; `.env.example` lists every one with an explanation.
The most important:

| Variable | Purpose | Default |
| --- | --- | --- |
| `APP_PUBLIC_URL` | Public URL of the web app; used in sign-in links and OAuth redirect URIs | `http://localhost:3000` |
| `APP_ADMIN_EMAILS` | Comma-separated emails that become administrators after verified sign-in | empty |
| `POSTGRES_PASSWORD` / `DATABASE_*` | Database credentials | development values |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS`, `MAIL_FROM` | Email sign-in. If `SMTP_HOST` is empty, email sign-in is shown as unavailable | Mailpit in Compose |
| `GOOGLE_CLIENT_ID`/`_SECRET`, `GITHUB_CLIENT_ID`/`_SECRET` | Optional OAuth sign-in; each provider is enabled only when both values are set | empty |
| `INGESTION_ENABLED` | Run the background ingestion worker and scheduler | `true` |
| `INGESTION_BOOTSTRAP` | Queue the launch guides and nationwide index on first start | `true` |
| `INGESTION_SEED_GUIDES` | Wikidata ids of launch destinations | `Q10086,Q1355` |
| `INGESTION_EXPANSION_PER_DAY` | Additional Wikivoyage destinations to ingest per day, largest first | `0` |
| `INGESTION_CONTACT` | Operator contact appended to the User-Agent sent to source APIs | empty |
| `COOKIE_SECURE` | Mark the session cookie `Secure` (set `true` behind HTTPS) | `false` |
| `TRUST_FORWARDED_HEADERS` | Use `X-Forwarded-For` for rate limiting; only when the API sits behind a trusted proxy | `false` (Compose sets `true`) |

Lifecycle, ranking, coverage and trust thresholds are in `backend/src/main/resources/application.yml`
(`app.freshness`, `app.ranking`, `app.coverage`, `app.trust`) and can be overridden with standard Spring Boot
environment variables (dots become underscores and dashes are dropped), for example `APP_FRESHNESS_VOLATILEDAYS=90`.

### Production notes

- Put the web container behind HTTPS, set `APP_PUBLIC_URL` to the public origin and `COOKIE_SECURE=true`.
- Replace Mailpit with your provider’s SMTP settings and remove the `mailpit` service.
- Use a strong `POSTGRES_PASSWORD`; back up the `db-data` volume.
- Run a single API instance. Ingestion runs one job at a time in that instance, and rate limiting is in memory.
- Set `INGESTION_CONTACT`, as the Wikimedia API etiquette asks.
- See [SECURITY.md](SECURITY.md) and [docs/operations.md](docs/operations.md).

## Ingesting real data

Ingestion runs inside the API as a Postgres-backed job queue. Jobs are idempotent: re-running them never creates
duplicates, and unchanged article revisions only refresh source timestamps.

| Job | What it does | Source and licence |
| --- | --- | --- |
| `wikidata_index` | Nationwide index of states, districts and destinations, with aliases and relations; or specific items | Wikidata, CC0 |
| `wikivoyage_guide` | Parses a destination’s Wikivoyage article (and large cities’ district articles) into entries and typed facts | English Wikivoyage, CC BY-SA 4.0 |
| `climate` | Monthly climate averages from ten years of daily ERA5 reanalysis | Open-Meteo, CC BY 4.0 |
| `place_image` | Destination image linked from Wikidata, with author and licence | Wikimedia Commons, per file |
| `osm_extract` | Nationwide points of interest from the Geofabrik India extract (MD5-verified, stream-parsed, two passes for building centroids); also links place nodes to indexed destinations | OpenStreetMap, ODbL 1.0 |
| `overture_places` | Overture Maps places around indexed destinations, from the release’s GeoParquet files (catalogued size and row count verified), matched against OSM | Overture Maps, CDLA Permissive 2.0 / Apache 2.0 / CC0 per dataset |
| `heritage_monuments` | Monuments carrying an Archaeological Survey of India number (Wikidata P1371), matched to mapped places | Wikidata, CC0 |
| `quality_evaluation` | Re-evaluates a source’s quality report on demand (`sourceId` parameter) | — |
| `resolve_request` | Resolves a reader’s destination request through the index and Wikidata search | Wikidata |
| `coverage_refresh` | Recomputes coverage status from stored data (runs every six hours) | — |

### Source lifecycle and quality gate

Every source goes through the same recorded lifecycle: **fetch → validate → parse → normalise → resolve →
persist → quality → publish**. Each run is stored in `source_sync_run` with real counts (fetched, parsed,
rejected by reason, new, changed, unchanged, removed, published) and warnings, so a failed download, a parse
error, a failed quality gate and a successful-but-empty sync are distinguishable. A run that fetches zero
records, or whose count changes sharply from the previous run of the same scope, is flagged.

After each import a source-specific evaluator writes a `quality_report`: schema validity, completeness,
geographic validity, duplicates, consistency across sources, freshness, provenance and coverage, each with a
threshold and a severity, plus a stratified sample for manual inspection. A failed critical check marks the
source `failed` and its data is not published (mapped places from it are withheld). Reports are public at
`/sources/{id}` and `GET /api/sources/{id}/quality`. See [docs/data-quality.md](docs/data-quality.md) for the
checks, thresholds and the latest measured results.

Map sources (OSM, Overture, ASI numbers) feed one shared POI pipeline: records keep their source id, version,
release and original attributes; identities are resolved by source id, then Wikidata id, then name similarity
and distance within compatible categories; uncertain matches are decided by ordered evidence rules (shared
phone or website, name variants at a given distance, chain branches) and each decision records its rule.
OSM areas mapped as multipolygon relations (parks, forts, campuses) are placed at the centre of their outer ways. Records
missing from a full release are removed only if fewer than 20% are missing (30% for area-scoped imports);
otherwise the deletion is skipped and flagged as a likely truncated release. Mapped places are shown in guides
as unreviewed map data, never as recommendations.

Ways to ingest more destinations:

- **Reader requests.** Requesting a place (no account needed) resolves it and, if Wikivoyage covers it, ingests
  its guide automatically.
- **Operations console.** Sign in as an administrator, open `/admin`, choose “Ingest Wikivoyage guide” and a
  destination slug (for example `hampi` or `kochi`).
- **Gradual expansion.** Set `INGESTION_EXPANSION_PER_DAY` to ingest the most prominent Wikivoyage destinations
  a few at a time.
- **API.** `POST /api/admin/ingestion/jobs` with `{"type":"wikivoyage_guide","placeSlug":"hampi"}` (admin
  session and CSRF token required).

Each source can be disabled in the console (or `PATCH /api/admin/sources/{id}`); disabled sources are never
contacted. Destinations whose core content is at least 60% community-verified “graduate”: collected sources are
no longer consumed for them. See [docs/data-sources.md](docs/data-sources.md) for access methods, attribution
and retention.

## Contributor sign-in and administration

- **Email sign-in.** Contributors receive a single-use link valid for 15 minutes. In development, open Mailpit
  at http://localhost:8025 to read it. Only a hash of the token is stored.
- **OAuth (optional).** Register an OAuth app with Google or GitHub using the redirect URI
  `${APP_PUBLIC_URL}/login/oauth2/code/google` (or `/github`) and set the client id and secret. The sign-in page
  shows only the methods that are configured.
- **Administrators.** Sign in with an email listed in `APP_ADMIN_EMAILS`. Administrators run ingestion, toggle
  sources, appoint city stewards and moderate everything.
- **Trust tiers.** Contributor → trusted contributor happens automatically after 10 accepted contributions or
  verifications with at most 10% rejected, on an account at least 14 days old; repeated rejections or an upheld
  abuse report demote. City stewards are appointed by administrators for a destination (and the places within it).

## Testing

| Suite | Where | What it covers |
| --- | --- | --- |
| Backend unit tests | `backend/src/test/java/.../{ranking,guide,ingestion,common}` | Ranking determinism, constraints and explanations; freshness lifecycle; price parsing; wikitext parsing and mapping; place classification; climate aggregation; URL safety |
| Backend integration tests | `backend/src/test/java/.../it` | Real PostGIS via Testcontainers: migrations, search and aliases, disambiguation, guide API, preference ranking, budgets, ingestion idempotency and supersession, failure recording, sign-in, CSRF, authorisation, moderation, verification rules, duplicate detection, audit log, account deletion, destination requests |
| Frontend unit tests | `frontend/tests` | Search autocomplete and keyboard navigation, preferences → API parameters, fact provenance and status labels, coverage and empty states, pagination, budget states, form validation, axe accessibility checks |
| End-to-end tests | `frontend/e2e` | Real stack: alias search to guide, preference ranking, provenance and fact history, honest empty guides, destination requests, mobile layout, accessibility scans, and the contributor → moderation → publication loop |

Backend integration tests start a PostGIS container. On Apple Silicon, set
`TEST_POSTGIS_IMAGE=imresamu/postgis:17-3.5` for speed. External sources are replaced in tests by a local stub
serving synthetic fixtures (`backend/src/test/resources/fixtures`); fixtures are never used outside tests.

End-to-end tests run against a running stack that has ingested at least one guide. They read sign-in emails from
Mailpit and need an administrator email:

```bash
cd frontend && npx playwright install chromium
```

```bash
cd frontend && E2E_BASE_URL=http://localhost:3000 E2E_ADMIN_EMAIL=admin@example.test npm run e2e
```

`E2E_ADMIN_EMAIL` must be listed in the stack’s `APP_ADMIN_EMAILS`. `E2E_GUIDE_SLUG` (default `mysore`) selects
the guide used by the tests.

## Architecture

```
browser ──► Next.js (App Router, SSR) ──/api proxy──► Spring Boot API ──► PostgreSQL + PostGIS
                                                         │
                                         ingestion worker│(Postgres job queue)
                                                         ▼
                                Wikidata · Wikivoyage · Open-Meteo · Wikimedia Commons
```

- **Read path:** destination + preferences → indexed queries (cached per destination, invalidated after commits)
  → deterministic ranking in memory → evidence-backed response. No external call or AI happens while reading.
- **Write path:** contributions and verifications are validated, rate-limited, written in transactions with
  moderation items and audit entries, and applied only when accepted.
- **Ingestion:** independent jobs with retries, backoff, heartbeats, per-source sync status and idempotent upserts.

Backend: Java 21, Spring Boot 4.1 (Web MVC, Security, Session JDBC, Validation, Actuator, Mail, OAuth2 client),
Flyway, JdbcClient, Caffeine, springdoc-openapi. Frontend: Next.js 16 App Router, React 19, TypeScript strict,
Tailwind CSS 4, Radix UI primitives in a shadcn-style component set, Lucide icons, Zod.

Design decisions and their reasons (JdbcClient instead of JPA, a Postgres job queue instead of Spring Batch,
session cookies through a same-origin proxy, the fact model and coverage rules) are in
[docs/architecture.md](docs/architecture.md).

## API

OpenAPI: `GET /v3/api-docs`, Swagger UI at `/swagger-ui.html` on the API. Highlights:

| Endpoint | Access |
| --- | --- |
| `GET /api/search?q=` | public |
| `GET /api/destinations`, `GET /api/destinations/{slug}` | public |
| `GET /api/destinations/{slug}/sections/{eat\|stay\|explore}?food=&budget=&comfort=&interests=&maxPrice=&accessibleOnly=&category=&page=&size=` | public |
| `GET /api/destinations/{slug}/{neighbourhoods\|budget\|getting-around\|tips}` | public |
| `GET /api/facts/{id}` | public |
| `POST /api/requests`, `GET /api/requests/{code}`, `POST /api/requests/{code}/choose` | public (CSRF token) |
| `GET /api/sources`, `GET /api/status/ingestion`, `GET /api/meta` | public |
| `GET /api/auth/session`, `POST /api/auth/email/start`, `POST /api/auth/email/verify`, `POST /api/auth/logout` | public |
| `POST /api/facts/{id}/verifications`, `POST /api/contributions`, `POST /api/flags`, `/api/me/**` | contributor |
| `/api/moderation/**` | trusted contributor, steward or admin |
| `/api/admin/**` | admin |

Errors use RFC 9457 problem documents with a stable `code`. State-changing requests need the `X-XSRF-TOKEN`
header matching the `XSRF-TOKEN` cookie (issued by `GET /api/auth/session`).

## Licences

- Software: [Apache-2.0](LICENSE).
- Data is **not** covered by the software licence; it keeps its source’s licence. See
  [DATA_LICENSES.md](DATA_LICENSES.md) and the `/sources` page of a running instance.
- Contributions made through the platform are licensed CC BY-SA 4.0 by their contributors.

See also [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), [SECURITY.md](SECURITY.md)
and [docs/data-retention.md](docs/data-retention.md).
