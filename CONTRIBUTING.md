# Contributing

Thanks for helping. There are two kinds of contribution:

1. **Guide content.** If you know a place, sign in on a running instance and use “Still true?”, corrections,
   reports and “Add to the guide”. That content is moderated in the product and licensed CC BY-SA 4.0. Please do
   not open pull requests that add destination data to this repository.
2. **Code and documentation**, through pull requests to this repository (Apache-2.0).

## Ground rules for code

- **No fabricated data.** Never add hardcoded destinations, sample guides, placeholder API responses, invented
  prices, ratings or citations to application code. Test fixtures belong under `src/test` or `frontend/tests`
  only, and must be clearly synthetic.
- **Honest states.** If data is missing, unconfigured or stale, show that plainly. Every visible control must work
  or be clearly unavailable.
- **Provenance first.** New facts need a source, a type (stable, semi-stable, volatile), an observation date or an
  explicit “unknown”, and evidence.
- **Respect sources.** New adapters must document the access method, licence, attribution and rate limits in the
  source registry migration and in `DATA_LICENSES.md`, identify themselves with the shared User-Agent, pace their
  requests and be idempotent.

## Development setup

See the README’s [Local development](README.md#local-development) section. Before opening a pull request:

```bash
cd backend && mvn verify
```

```bash
cd frontend && npm run typecheck && npm run lint && npm test && npm run build
```

Database changes go in a new Flyway migration (`backend/src/main/resources/db/migration/V<n>__description.sql`);
never edit a migration that has been released.

## Pull requests

- Keep changes focused and explain the user-facing effect.
- Add or update tests for behaviour changes (ranking, freshness, ingestion, permissions especially).
- UI changes should work at mobile, tablet and desktop widths, keep keyboard access and visible focus, and pass
  the axe checks in the test suite.
- By submitting a pull request you agree that your contribution is licensed under Apache-2.0.

## Reporting problems

Open an issue with steps to reproduce. For security issues, follow [SECURITY.md](SECURITY.md) instead.
