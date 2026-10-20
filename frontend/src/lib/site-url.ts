const FALLBACK = "http://localhost:3000";

/**
 * The site's public address, for absolute links in page metadata. An unset or empty value (an unset
 * Docker build argument arrives as an empty string) or a malformed one falls back to localhost
 * instead of failing the build. SITE_URL is read when the server runs; NEXT_PUBLIC_SITE_URL is fixed at build time.
 */
export function siteUrl(env: Record<string, string | undefined> = process.env): URL {
  for (const candidate of [env.SITE_URL, env.NEXT_PUBLIC_SITE_URL]) {
    const value = candidate?.trim();
    if (!value) continue;
    try {
      return new URL(value);
    } catch {
      // Not a valid address; try the next one.
    }
  }
  return new URL(FALLBACK);
}
