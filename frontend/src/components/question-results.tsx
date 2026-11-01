"use client";

import Link from "next/link";
import * as React from "react";
import { CoverageBadge } from "@/components/coverage-badge";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import type { QuestionAnswer } from "@/lib/types";
import { kindLabel, safeHref } from "@/lib/utils";

/**
 * Answers a question-like search with places from Saarthi's own data. The model only reads the question into the
 * filters shown as chips; every place shows the sourced reasons it matched. Falls back to name search (children)
 * when the question cannot be read.
 */
export function QuestionResults({ q, children }: { q: string; children: React.ReactNode }) {
  const [answer, setAnswer] = React.useState<QuestionAnswer | null>(null);
  const [failed, setFailed] = React.useState(false);

  React.useEffect(() => {
    // The parent keys this component by the question, so a new question starts from a fresh state.
    const controller = new AbortController();
    fetch(`/api/search/ask?q=${encodeURIComponent(q)}`, { signal: controller.signal })
      .then((r) => (r.ok ? (r.json() as Promise<QuestionAnswer>) : Promise.reject(new Error(String(r.status)))))
      .then(setAnswer)
      .catch((e: unknown) => {
        if (!(e instanceof DOMException && e.name === "AbortError")) setFailed(true);
      });
    return () => controller.abort();
  }, [q]);

  if (failed || (answer && answer.mode !== "question")) {
    return (
      <>
        {answer?.mode === "unavailable" || failed ? (
          <p className="mt-6 max-w-2xl text-sm text-muted">
            Saarthi could not read this as a question right now, so here are places matching the words instead.
          </p>
        ) : null}
        {children}
      </>
    );
  }
  if (!answer) {
    return (
      <p className="mt-8 text-sm text-muted" role="status">
        Reading your question… this can take a few seconds.
      </p>
    );
  }

  return (
    <section className="mt-8 space-y-6" aria-labelledby="question-heading">
      <div>
        <h2 id="question-heading" className="text-lg font-semibold">
          How Saarthi read your question
        </h2>
        <ul className="mt-2 flex flex-wrap gap-2" aria-label="Filters">
          {answer.understood.map((u) => (
            <li key={u}>
              <Badge>{u}</Badge>
            </li>
          ))}
        </ul>
        {answer.unsupported.length ? (
          <p className="mt-2 text-sm text-muted">
            Saarthi can&apos;t filter by {answer.unsupported.map((u) => `“${u}”`).join(", ")} yet, so those words were not used.
          </p>
        ) : null}
        {answer.notes.map((n) => (
          <p key={n} className="mt-1 text-sm text-muted">
            {n}
          </p>
        ))}
        <p className="mt-2 text-xs text-muted">
          An AI model only turned your words into these filters. The places and every reason below come from Saarthi&apos;s
          guides and their sources.
        </p>
      </div>

      {answer.results.length ? (
        <ol className="divide-y divide-line rounded-lg border border-line bg-surface">
          {answer.results.map((r) => (
            <li key={r.slug} className="flex flex-col gap-2 p-4 sm:flex-row sm:items-start sm:justify-between">
              <div className="min-w-0">
                <Link href={`/d/${r.slug}`} className="font-serif text-lg font-semibold hover:text-accent hover:underline">
                  {r.name}
                </Link>
                <p className="text-sm text-muted">{[kindLabel(r.kind), r.state].filter(Boolean).join(" · ")}</p>
                <ul className="mt-2 space-y-1 text-sm text-ink-2">
                  {r.reasons.map((why) => {
                    const href = safeHref(why.sourceUrl);
                    return (
                      <li key={why.text}>
                        {why.text}
                        {why.sourceName ? (
                          <span className="text-xs text-muted">
                            {" "}
                            ·{" "}
                            {href ? (
                              <a href={href} target="_blank" rel="noopener noreferrer" className="underline">
                                {why.sourceName}
                              </a>
                            ) : (
                              why.sourceName
                            )}
                          </span>
                        ) : null}
                      </li>
                    );
                  })}
                </ul>
              </div>
              <CoverageBadge status={r.coverageStatus} />
            </li>
          ))}
        </ol>
      ) : (
        <Notice tone="info" title="No guided place matches all of this yet">
          Try fewer conditions, or a longer distance. Saarthi only lists places it has guides for, and more are added
          every day.
        </Notice>
      )}
      {answer.origin && answer.results.length ? (
        <p className="text-sm text-muted">
          Planning from {answer.origin.name}?{" "}
          <Link className="text-accent underline" href={`/trips/weekend-from/${answer.origin.slug}`}>
            See all weekend trips
          </Link>{" "}
          (shown only where Saarthi has drive times).
        </p>
      ) : null}
    </section>
  );
}
