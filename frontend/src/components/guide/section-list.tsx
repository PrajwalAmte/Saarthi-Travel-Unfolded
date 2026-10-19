import Link from "next/link";
import type { SectionView } from "@/lib/types";
import { cn } from "@/lib/utils";
import { Notice } from "../ui/notice";
import { EntryCard } from "./entry-card";
import { FactCard } from "./fact-card";

const EMPTY: Record<string, string> = {
  eat: "No places to eat have been published for this destination yet.",
  stay: "No places to stay have been published for this destination yet.",
  explore: "No sights, activities or shopping have been published for this destination yet.",
};

export function SectionList({
  data,
  slug,
  destinationId,
  basePath,
  params,
}: {
  data: SectionView;
  slug: string;
  destinationId: string;
  basePath: string;
  params: URLSearchParams;
}) {
  const category = params.get("category") ?? "";
  const link = (patch: Record<string, string | null>) => {
    const p = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v === null || v === "") p.delete(k);
      else p.set(k, v);
    }
    const qs = p.toString();
    return qs ? `${basePath}?${qs}` : basePath;
  };
  const { entries } = data;
  const firstRank = entries.page * entries.size + 1;

  return (
    <div className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_18rem]">
      <div className="min-w-0">
        {data.categories.length > 1 ? (
          <nav aria-label="Categories" className="mb-4 flex flex-wrap gap-2">
            {[{ key: "", label: "All", count: data.categories.reduce((s, c) => s + c.count, 0) }, ...data.categories].map((c) => (
              <Link
                key={c.key || "all"}
                href={link({ category: c.key || null, page: null })}
                aria-current={category === c.key ? "true" : undefined}
                className={cn(
                  "inline-flex h-9 items-center gap-1.5 rounded-md border px-3 text-sm",
                  category === c.key ? "border-accent bg-accent-soft text-accent" : "border-line-strong text-ink-2 hover:border-ink-2",
                )}
              >
                {c.label} <span className="text-xs text-muted">{c.count}</span>
              </Link>
            ))}
          </nav>
        ) : null}

        <div className="space-y-3">
          {data.preferencesApplied && data.personalizationApplied ? (
            <p className="text-sm text-muted">Sorted for your preferences. Open “Why it&apos;s listed” on any place to see why.</p>
          ) : null}
          {data.personalizationNote ? <Notice tone="info">{data.personalizationNote}</Notice> : null}
          {data.constraintNotes.map((n) => (
            <Notice key={n} tone="info">
              {n}
            </Notice>
          ))}
        </div>

        {entries.items.length === 0 ? (
          <div className="mt-6 rounded-lg border border-dashed border-line-strong p-8 text-center">
            <p className="font-medium text-ink">{data.totalPublished === 0 ? EMPTY[data.section] : "Nothing matches these filters."}</p>
            <p className="mt-1 text-sm text-muted">
              Know a good place here?{" "}
              <Link className="text-accent underline" href={`/contribute/${slug}`}>
                Suggest it
              </Link>
              . Suggestions are reviewed before they are published.
            </p>
          </div>
        ) : (
          <div className="mt-2">
            {entries.items.map((e, i) => (
              <EntryCard key={e.id} entry={e} destinationId={destinationId} destinationSlug={slug} rank={firstRank + i} />
            ))}
          </div>
        )}

        {entries.totalPages > 1 ? (
          <nav aria-label="Pages" className="mt-6 flex items-center justify-between border-t border-line pt-4 text-sm">
            {entries.page > 0 ? (
              <Link className="text-accent underline" href={link({ page: String(entries.page - 1) })} rel="prev">
                ← Previous
              </Link>
            ) : (
              <span />
            )}
            <span className="text-muted">
              Page {entries.page + 1} of {entries.totalPages} · {entries.totalItems} entries
            </span>
            {entries.page + 1 < entries.totalPages ? (
              <Link className="text-accent underline" href={link({ page: String(entries.page + 1) })} rel="next">
                Next →
              </Link>
            ) : (
              <span />
            )}
          </nav>
        ) : null}
      </div>

      <aside className="space-y-6">
        {data.specialities.length ? (
          <section>
            <h2 className="text-lg font-semibold">{data.section === "explore" ? "Local products" : "Local specialities"}</h2>
            <div className="mt-1">
              {data.specialities.map((f) => (
                <FactCard key={f.id} fact={{ ...f, text: f.text.replace(new RegExp(`^${escapeRegExp(f.heading ?? "")} — `), "") }} destinationId={destinationId} />
              ))}
            </div>
          </section>
        ) : null}
        {data.notes.length ? (
          <section>
            <h2 className="text-lg font-semibold">{data.section === "stay" ? "Where to stay: notes" : "Good to know"}</h2>
            <div className="mt-1">
              {data.notes.map((f) => (
                <FactCard key={f.id} fact={f} destinationId={destinationId} />
              ))}
            </div>
          </section>
        ) : null}
        {data.externalLinks.length ? (
          <section className="rounded-md border border-line p-4 text-sm">
            <h2 className="font-serif text-base font-semibold">Check live rates</h2>
            <ul className="mt-2 space-y-1">
              {data.externalLinks.map((l) => (
                <li key={l.url}>
                  <a className="text-accent underline" href={l.url} target="_blank" rel="noopener noreferrer nofollow">
                    {l.label}
                  </a>
                </li>
              ))}
            </ul>
            <p className="mt-2 text-xs text-muted">{data.externalLinks[0]?.note}</p>
          </section>
        ) : null}
        <section className="rounded-md bg-sunken/70 p-4 text-sm text-ink-2">
          <p className="font-medium text-ink">Know {data.section === "eat" ? "a place to eat" : data.section === "stay" ? "a good stay" : "something worth seeing"} here?</p>
          <Link className="mt-1 inline-block text-accent underline" href={`/contribute/${slug}`}>
            Add it to the guide
          </Link>
        </section>
      </aside>
    </div>
  );
}

function escapeRegExp(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
