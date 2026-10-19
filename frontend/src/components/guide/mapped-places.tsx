import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { apiGetOrNull } from "@/lib/server-api";
import type { MappedPlaces as MappedPlacesData } from "@/lib/types";
import { cn, googleMapsHref, safeHref } from "@/lib/utils";

const GROUPS: { key: string; label: string }[] = [
  { key: "see", label: "See" },
  { key: "do", label: "Do" },
  { key: "eat", label: "Eat" },
  { key: "drink", label: "Drink" },
  { key: "stay", label: "Stay" },
  { key: "buy", label: "Buy" },
  { key: "transport", label: "Transport" },
];

const DETAIL_LABELS: Record<string, string> = {
  address: "Address",
  opening_hours: "Hours (as mapped)",
  phone: "Phone",
  website: "Website",
  cuisine: "Cuisine",
  local_name: "Local name",
};

function categoryLabel(c: string) {
  return c.replaceAll("_", " ");
}

/**
 * Unreviewed places from open map data near a destination. Clearly separated from guide
 * entries: these are not recommendations and carry their source and licence on every row.
 */
export async function MappedPlaces({
  slug,
  group,
  page,
  basePath,
  compact = false,
}: {
  slug: string;
  group: string | null;
  page: number;
  basePath: string;
  compact?: boolean;
}) {
  const limit = compact ? 8 : 30;
  const qs = new URLSearchParams({ limit: String(limit), offset: String(page * limit) });
  if (group) qs.set("group", group);
  let data: MappedPlacesData | null = null;
  try {
    data = await apiGetOrNull<MappedPlacesData>(`/api/destinations/${slug}/mapped-places?${qs}`);
  } catch {
    data = null;
  }
  if (!data) {
    return (
      <Notice tone="error" className="mt-4">
        Mapped places could not be loaded right now.
      </Notice>
    );
  }
  const totalAll = Object.values(data.countsByGroup).reduce((a, b) => a + b, 0);
  const pages = Math.ceil(data.total / data.limit);
  const link = (g: string | null, p = 0) => {
    const q = new URLSearchParams();
    if (g) q.set("group", g);
    if (p > 0) q.set("page", String(p));
    const s = q.toString();
    return s ? `${basePath}?${s}` : basePath;
  };

  return (
    <section aria-labelledby="mapped-heading" className="space-y-4">
      <div>
        <h2 id="mapped-heading" className="text-2xl font-semibold">
          More places nearby
        </h2>
        <p className="mt-1 text-sm text-muted">
          From open map data, handy for finding what&apos;s around. These aren&apos;t reviewed picks, so check opening
          times before you go.
        </p>
      </div>
      {totalAll === 0 ? (
        <p className="text-sm text-muted">No places on the map near {data.destination} yet.</p>
      ) : (
        <>
          {!compact ? (
            <nav aria-label="Filter mapped places" className="flex flex-wrap gap-1.5">
              <Link
                href={link(null)}
                className={cn("rounded-md border px-2.5 py-1 text-sm", !group ? "border-accent bg-accent-soft" : "border-line")}
                aria-current={!group ? "page" : undefined}
              >
                All <span className="text-muted">{totalAll.toLocaleString("en-IN")}</span>
              </Link>
              {GROUPS.filter((g) => data.countsByGroup[g.key]).map((g) => (
                <Link
                  key={g.key}
                  href={link(g.key)}
                  className={cn("rounded-md border px-2.5 py-1 text-sm", group === g.key ? "border-accent bg-accent-soft" : "border-line")}
                  aria-current={group === g.key ? "page" : undefined}
                >
                  {g.label} <span className="text-muted">{(data.countsByGroup[g.key] ?? 0).toLocaleString("en-IN")}</span>
                </Link>
              ))}
            </nav>
          ) : null}
          {data.attribution.length ? <p className="text-xs text-muted">{data.attribution.join(" ")}</p> : null}
          <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
            {data.places.map((p) => (
              <li key={p.id} className="p-4">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <h3 className="font-medium text-ink">{p.name}</h3>
                  <span className="text-xs text-muted">
                    <a className="underline" href={googleMapsHref(p.latitude, p.longitude) ?? undefined} target="_blank" rel="noopener noreferrer">
                      Map
                    </a>
                    {" · "}
                    {categoryLabel(p.category)}
                    {p.distanceKm !== null ? ` · ${p.distanceKm} km from centre` : ""}
                  </span>
                </div>
                {p.heritage.length ? (
                  <div className="mt-1 flex flex-wrap gap-1">
                    {p.heritage.map((h) => (
                      <Badge key={h} tone="source">
                        {h}
                      </Badge>
                    ))}
                  </div>
                ) : null}
                {Object.keys(p.details).length ? (
                  <dl className="mt-2 grid gap-x-4 gap-y-1 text-sm sm:grid-cols-[max-content_1fr]">
                    {Object.entries(p.details).map(([k, v]) => (
                      <div key={k} className="contents">
                        <dt className="text-muted">{DETAIL_LABELS[k] ?? k}</dt>
                        <dd className="break-words text-ink-2">
                          {k === "website" && safeHref(v.value) ? (
                            <a className="underline" href={v.value} target="_blank" rel="noopener noreferrer nofollow">
                              {v.value.replace(/^https?:\/\//, "")}
                            </a>
                          ) : (
                            v.value
                          )}
                          {p.sources.length > 1 ? <span className="text-xs text-muted"> ({v.sourceId})</span> : null}
                        </dd>
                      </div>
                    ))}
                  </dl>
                ) : null}
                <p className="mt-2 text-xs text-muted">
                  {p.sources.map((s, i) => (
                    <span key={s.sourceId + s.externalId}>
                      {i > 0 ? " · " : ""}
                      {s.url && safeHref(s.url) ? (
                        <a className="underline" href={s.url} target="_blank" rel="noopener noreferrer">
                          {s.sourceName}
                        </a>
                      ) : (
                        s.sourceName
                      )}{" "}
                      ({s.licence})
                    </span>
                  ))}
                  {p.guideEntryId ? " · also in this guide" : ""}
                </p>
              </li>
            ))}
          </ul>
          {compact ? (
            <p className="text-sm">
              <Link href={basePath} className="underline">
                See all {totalAll.toLocaleString("en-IN")} places nearby
              </Link>
            </p>
          ) : pages > 1 ? (
            <nav aria-label="Pages" className="flex items-center justify-between text-sm">
              {page > 0 ? (
                <Link href={link(group, page - 1)} className="underline">
                  Previous
                </Link>
              ) : (
                <span />
              )}
              <span className="text-muted">
                Page {page + 1} of {pages}
              </span>
              {page + 1 < pages ? (
                <Link href={link(group, page + 1)} className="underline">
                  Next
                </Link>
              ) : (
                <span />
              )}
            </nav>
          ) : null}
        </>
      )}
    </section>
  );
}
