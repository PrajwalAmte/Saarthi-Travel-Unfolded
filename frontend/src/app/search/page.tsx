import Link from "next/link";
import { CoverageBadge } from "@/components/coverage-badge";
import { RequestForm } from "@/components/request-form";
import { SearchBox } from "@/components/search-box";
import { Notice } from "@/components/ui/notice";
import { preferencesToParams, parsePreferences } from "@/lib/preferences";
import { apiGet } from "@/lib/server-api";
import type { Page, SearchResult } from "@/lib/types";
import { kindLabel } from "@/lib/utils";

export const dynamic = "force-dynamic";
export const metadata = { title: "Destinations" };

type ListItem = { slug: string; name: string; kind: string; description: string | null; stateName: string | null; coverageStatus: string | null };

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

export default async function SearchPage({ searchParams }: Props) {
  const sp = await searchParams;
  const q = (Array.isArray(sp.q) ? sp.q[0] : sp.q)?.trim() ?? "";
  const prefs = preferencesToParams(parsePreferences(sp)).toString();
  let result: SearchResult | null = null;
  let failed = false;
  let guides: Page<ListItem> | null = null;
  let states: Page<ListItem> | null = null;
  try {
    if (q.length >= 2) {
      result = await apiGet<SearchResult>(`/api/search?q=${encodeURIComponent(q)}&limit=25`);
    } else {
      [guides, states] = await Promise.all([
        apiGet<Page<ListItem>>("/api/destinations?withGuide=true&size=48"),
        apiGet<Page<ListItem>>("/api/destinations?kind=state&size=50"),
      ]);
    }
  } catch {
    failed = true;
  }
  const hrefFor = (slug: string) => `/d/${slug}${prefs ? `?${prefs}` : ""}`;

  return (
    <div className="container-page py-8 sm:py-12">
      <h1 className="text-3xl font-semibold sm:text-4xl">{q ? `Destinations matching “${q}”` : "Find a destination"}</h1>
      <div className="mt-6 max-w-2xl">
        <SearchBox size="md" initialQuery={q} suffix={prefs} />
      </div>
      {failed ? (
        <Notice tone="error" className="mt-6 max-w-2xl" title="Search is unavailable">
          The guide service could not be reached. Please try again in a moment.
        </Notice>
      ) : null}

      {result ? (
        result.results.length ? (
          <section className="mt-8">
            <p className="text-sm text-muted">
              {result.results.length} indexed place{result.results.length === 1 ? "" : "s"}. Where several share a name, the
              state and district tell them apart.
            </p>
            <ul className="mt-4 divide-y divide-line rounded-lg border border-line bg-surface">
              {result.results.map((h) => (
                <li key={h.id} className="flex flex-col gap-2 p-4 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0">
                    <Link
                      href={h.kind === "neighbourhood" && h.parentCitySlug ? `/d/${h.parentCitySlug}/neighbourhoods` : hrefFor(h.slug)}
                      className="font-serif text-lg font-semibold hover:text-accent hover:underline"
                    >
                      {h.name}
                    </Link>
                    {h.matchedAlias ? <span className="ml-2 text-sm text-muted">matched “{h.matchedAlias}”</span> : null}
                    <p className="text-sm text-muted">
                      {[kindLabel(h.kind), h.parentCityName, h.districtName, h.stateName].filter(Boolean).join(" · ")}
                      {h.description ? ` — ${h.description}` : ""}
                    </p>
                  </div>
                  <CoverageBadge status={h.coverageStatus} />
                </li>
              ))}
            </ul>
            <p className="mt-4 text-sm text-muted">
              Not the place you meant?{" "}
              <Link className="text-accent underline" href={`/request?q=${encodeURIComponent(q)}`}>
                Request it
              </Link>
              .
            </p>
          </section>
        ) : (
          <section className="mt-8 max-w-2xl space-y-4">
            <Notice tone="info" title={`“${q}” is not in the index yet`}>
              No indexed destination matches this name or any of its known spellings. You can request it below; requests are
              looked up in Wikidata and ingested if an open source covers them.
            </Notice>
            <RequestForm initialQuery={q} />
          </section>
        )
      ) : null}

      {!q && guides ? (
        <section className="mt-10">
          <h2 className="text-2xl font-semibold">Destinations with guide content</h2>
          {guides.items.length === 0 ? (
            <p className="mt-2 text-muted">No guides have been published yet. Ingestion may still be running.</p>
          ) : (
            <ul className="mt-4 grid gap-x-6 sm:grid-cols-2 lg:grid-cols-3">
              {guides.items.map((g) => (
                <li key={g.slug} className="flex items-center justify-between gap-3 border-b border-line py-3">
                  <div className="min-w-0">
                    <Link href={hrefFor(g.slug)} className="font-medium hover:text-accent hover:underline">
                      {g.name}
                    </Link>
                    <p className="truncate text-xs text-muted">{[kindLabel(g.kind), g.stateName].filter(Boolean).join(" · ")}</p>
                  </div>
                  <CoverageBadge status={g.coverageStatus as never} />
                </li>
              ))}
            </ul>
          )}
        </section>
      ) : null}
      {!q && states && states.items.length ? (
        <section className="mt-10">
          <h2 className="text-2xl font-semibold">Browse by state</h2>
          <ul className="mt-4 flex flex-wrap gap-2">
            {states.items.map((s) => (
              <li key={s.slug}>
                <Link href={`/d/${s.slug}`} className="inline-flex rounded-md border border-line bg-surface px-3 py-1.5 text-sm hover:border-accent">
                  {s.name}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
