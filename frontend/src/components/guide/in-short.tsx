import type { InShortItem } from "@/lib/types";
import { safeHref } from "@/lib/utils";

const TOPIC: Record<string, string> = {
  best_for: "Best for",
  when_to_go: "When to go",
  getting_around: "Getting around",
  good_to_know: "Good to know",
};

/** A few sentences written by AI only from this guide's own facts; each names the sources it relies on. */
export function InShort({ items }: { items: InShortItem[] }) {
  if (!items.length) return null;
  return (
    <section aria-labelledby="in-short" className="rounded-lg border border-line bg-surface p-5">
      <h2 id="in-short" className="text-lg font-semibold">
        In short
      </h2>
      <dl className="mt-3 space-y-3">
        {items.map((i) => (
          <div key={i.topic} className="grid gap-1 sm:grid-cols-[9rem_1fr]">
            <dt className="text-sm font-medium text-muted">{TOPIC[i.topic] ?? i.topic}</dt>
            <dd className="text-ink-2">
              {i.text}{" "}
              <span className="text-xs text-muted">
                (from{" "}
                {i.sources.map((s, n) => (
                  <span key={`${s.factId}-${s.sourceName}`}>
                    {n > 0 ? ", " : ""}
                    {s.url && safeHref(s.url) ? (
                      <a href={s.url} target="_blank" rel="noopener noreferrer" className="underline">
                        {s.sourceName}
                      </a>
                    ) : (
                      s.sourceName
                    )}
                  </span>
                ))}
                )
              </span>
            </dd>
          </div>
        ))}
      </dl>
      <p className="mt-3 text-xs text-muted">Written by AI from the facts in this guide, and checked to add nothing they do not say.</p>
    </section>
  );
}
