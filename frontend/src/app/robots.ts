import type { MetadataRoute } from "next";
import { siteUrl } from "@/lib/site-url";

// Built per request so the address comes from SITE_URL at run time, not whatever was set at build time.
export const dynamic = "force-dynamic";

/**
 * Every crawler is welcome on the public guide, including search engines and AI crawlers (OAI-SearchBot, GPTBot,
 * ClaudeBot, PerplexityBot, Google-Extended): the content is open data and is meant to be found and reused.
 * Personal, transactional and API pages are kept out so crawls go to the guides.
 */
export default function robots(): MetadataRoute.Robots {
  const base = siteUrl();
  return {
    rules: [
      {
        userAgent: "*",
        allow: "/",
        disallow: ["/admin", "/account", "/moderation", "/signin", "/login", "/auth/", "/oauth2/", "/requests/", "/contribute/", "/api/"],
      },
    ],
    sitemap: new URL("/sitemap.xml", base).toString(),
    host: base.origin,
  };
}
