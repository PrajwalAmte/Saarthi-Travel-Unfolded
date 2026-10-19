# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub’s “Report a vulnerability” (Security advisories) on this
repository. Do not open public issues for security problems. Include steps to reproduce and the affected
version or commit. We aim to acknowledge reports within a week.

## What the application does

- **Authentication.** Passwordless email links (single-use, 15-minute expiry, only a SHA-256 hash stored) and
  optional OAuth through Spring Security. Sessions are server-side (Spring Session JDBC); the session id is
  rotated at sign-in and the cookie is `HttpOnly`, `SameSite=Lax` and `Secure` when `COOKIE_SECURE=true`.
- **Authorisation** is enforced on the server for every request. Roles are re-read from the database on each
  request, so promotions, demotions and suspensions take effect immediately. Clients cannot set their own tier,
  approve their own submissions or review items outside their role.
- **CSRF.** All state-changing requests require a token (double-submit cookie with Spring Security’s SPA
  handler).
- **Input.** Request bodies are validated with Bean Validation and Zod; SQL uses bound parameters only; user text
  is rendered as text (React escaping); links are restricted to public `http(s)` URLs and rendered with
  `rel="noopener noreferrer nofollow"`. The platform never fetches URLs supplied by users.
- **Rate limits** apply to search, destination requests, sign-in emails, contributions, verifications and flags.
  Limits are in memory per API instance; contribution volume is also checked in the database.
- **Errors** never include stack traces or internal messages; logs avoid tokens and personal data.
- **Headers.** The web app sets a Content-Security-Policy, `X-Frame-Options: DENY`, `nosniff`, a referrer
  policy and a permissions policy.

## Known limitations

- The Content-Security-Policy allows inline scripts because Next.js hydration requires them without nonces.
- Rate limiting is per instance; run a single API instance or add a shared limiter before scaling out.
- `TRUST_FORWARDED_HEADERS` must only be enabled when the API is reachable exclusively through a trusted proxy.
- Swagger UI and `/v3/api-docs` are public; disable them with `SPRINGDOC_API_DOCS_ENABLED=false` and
  `SPRINGDOC_SWAGGER_UI_ENABLED=false` if you prefer.

Using Spring Security does not by itself make a deployment secure: keep dependencies updated, serve over HTTPS,
protect the database and review the configuration.
