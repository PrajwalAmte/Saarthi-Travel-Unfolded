import type { MetadataRoute } from "next";
import { apiGet } from "@/lib/server-api";
import { siteUrl } from "@/lib/site-url";

export const dynamic = "force-dynamic";

type Entry = { slug: string; kind: string; lastModified: string | null; tabs: string[] };

/**
 * Pages worth indexing: each destination with a guide, its tabs that have content, and state hubs. Places without a
 * guide are left out (their pages are marked noindex), so crawlers spend their time on real content.
 */
export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  const base = siteUrl();
  const url = (path: string) => new URL(path, base).toString();
  const pages: MetadataRoute.Sitemap = [
    { url: url("/"), changeFrequency: "daily", priority: 1 },
    { url: url("/search"), changeFrequency: "daily", priority: 0.6 },
    { url: url("/about"), changeFrequency: "monthly", priority: 0.5 },
    { url: url("/sources"), changeFrequency: "weekly", priority: 0.4 },
  ];
  let entries: Entry[] = [];
  try {
    entries = await apiGet<Entry[]>("/api/destinations/sitemap", { timeoutMs: 60_000 });
  } catch {
    // The guide service may be waking up; a short sitemap now beats a failed one, and crawlers come back.
    return pages;
  }
  try {
    const origins = await apiGet<{ slug: string }[]>("/api/trips/origins", { timeoutMs: 30_000 });
    for (const o of origins) {
      pages.push({ url: url(`/trips/weekend-from/${o.slug}`), changeFrequency: "weekly", priority: 0.8 });
    }
  } catch {
    // Trip pages are added on the next crawl.
  }
  for (const e of entries) {
    const lastModified = e.lastModified ? new Date(e.lastModified) : undefined;
    pages.push({ url: url(`/d/${e.slug}`), lastModified, changeFrequency: "weekly", priority: e.tabs.length ? 0.8 : 0.7 });
    for (const tab of e.tabs) {
      pages.push({ url: url(`/d/${e.slug}/${tab}`), lastModified, changeFrequency: "weekly", priority: 0.6 });
    }
  }
  return pages;
}
