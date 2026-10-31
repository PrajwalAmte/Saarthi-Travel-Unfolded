"use client";

import Link from "next/link";
import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import type { FactCheck, Page } from "@/lib/types";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";
import { Notice } from "./ui/notice";

export type AiFlag = {
  id: string;
  entryId: string;
  destinationSlug: string;
  destinationName: string;
  section: string;
  title: string;
  area: string | null;
  description: string | null;
  fitsSection: boolean;
  inDestination: "yes" | "no" | "unclear";
  issues: string[];
  suggestedSection: string | null;
  confidence: number;
  reason: string;
  model: string;
  createdAt: string;
};

export type AiUsage = {
  active: boolean;
  model: string;
  fallbackModel: string | null;
  requestsToday: number;
  dailyBudget: number;
  tokensToday: number;
  openFlags: number;
  reviewedListings: number;
  queuedJobs: number;
  decidedAutomatically: number;
  models?: { name: string; requestsToday: number; requestsPerDay: number; requestsPerMinute: number }[];
};

export type AiDecision = {
  id: string;
  destinationSlug: string;
  destinationName: string;
  title: string;
  section: string;
  resolution: "moved" | "hidden" | "kept" | "noted";
  previousSection: string | null;
  automatic: boolean;
  reason: string;
  confidence: number;
  decidedAt: string;
};

export type AiPreview = {
  slug: string;
  name: string;
  published: boolean;
  listings: number;
  enriched: number;
  summariesAccepted: number;
  summariesRejected: number;
  tagged: number;
  factsExtracted: number;
  inShort: { topic: string; text: string }[];
  inShortRejected: { topic: string; text: string; reason: string }[];
  entries: {
    entryId: string;
    section: string;
    title: string;
    area: string | null;
    original: string | null;
    summary: string | null;
    summaryStatus: "accepted" | "rejected" | "not_needed";
    summaryRejection: string | null;
    tags: { tag: string; quote: string }[];
    extracted: { kind: string; quote: string }[];
  }[];
};

const SECTION_TEXT: Record<string, string> = {
  eat: "places to eat",
  drink: "cafés and drinks",
  stay: "places to stay",
  see: "sights",
  do: "things to do",
  buy: "shopping",
};

/** Where the reviewer would move a listing, when that is a real section different from its current one. */
const CHECK_TEXT: Record<FactCheck["verdict"], string> = {
  confirmed: "confirmed on the website",
  changed: "changed to",
  not_stated: "not stated on the website",
  closed: "website says it has closed (left for you to decide)",
  unreadable: "website could not be read",
  rejected: "answer not borne out by the page, nothing changed",
};

function moveTarget(f: AiFlag): string | null {
  const s = f.suggestedSection;
  return s && s !== f.section && s in SECTION_TEXT ? s : null;
}

const ISSUE_TEXT: Record<string, string> = {
  wrong_section: "wrong section",
  outside_destination: "not in this destination",
  about_another_business: "describes another place",
  broken_text: "broken text",
  closed: "says it closed",
  too_thin: "very short",
};

/**
 * Listings the AI reviewer found doubtful. The reviewer only flags; an admin decides. Accepting hides the listing
 * from the guide with the reason recorded; dismissing leaves it as it is.
 */
export function AiReviewPanel() {
  const [usage, setUsage] = React.useState<AiUsage | null>(null);
  const [flags, setFlags] = React.useState<Page<AiFlag> | null>(null);
  const [message, setMessage] = React.useState<{ tone: "success" | "error"; text: string } | null>(null);
  const [busy, setBusy] = React.useState<string | null>(null);
  const [version, setVersion] = React.useState(0);
  const [published, setPublished] = React.useState<boolean | null>(null);
  const [previewSlug, setPreviewSlug] = React.useState("");
  const [preview, setPreview] = React.useState<AiPreview | null>(null);
  const [decisions, setDecisions] = React.useState<Page<AiDecision> | null>(null);
  const [checks, setChecks] = React.useState<Page<FactCheck> | null>(null);

  React.useEffect(() => {
    let cancelled = false;
    Promise.all([
      api<AiUsage>("/api/admin/ai-review/usage"),
      api<Page<AiFlag>>("/api/admin/ai-review/flags?size=20"),
      api<{ published: boolean }>("/api/admin/ai-review/publish"),
      api<Page<AiDecision>>("/api/admin/ai-review/decisions?automatic=true&size=15"),
      api<Page<FactCheck>>("/api/admin/ai-review/checks?size=15").catch(() => null),
    ])
      .then(([u, f, p, d, c]) => {
        if (cancelled) return;
        setUsage(u);
        setFlags(f);
        setPublished(p.published);
        setDecisions(d);
        setChecks(c);
      })
      .catch((e) => {
        if (!cancelled) setMessage({ tone: "error", text: problemMessage(e) });
      });
    return () => {
      cancelled = true;
    };
  }, [version]);

  async function act(key: string, fn: () => Promise<{ message?: string }>) {
    setBusy(key);
    setMessage(null);
    try {
      const r = await fn();
      setMessage({ tone: "success", text: r.message ?? "Done." });
      setVersion((v) => v + 1);
    } catch (e) {
      setMessage({ tone: "error", text: problemMessage(e) });
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="space-y-3" aria-labelledby="ai-review-heading">
      <h2 id="ai-review-heading" className="text-2xl font-semibold">
        AI review of listings
      </h2>
      <p className="text-sm text-muted">
        A language model reads each imported listing. When it is confident, the listing is moved to the right section or
        hidden (an ATM, a closed shop, a hotel in another city) without you; each of those can be undone below. Only the
        doubtful ones wait here for you.
      </p>
      {message ? (
        <Notice tone={message.tone} role="status">
          {message.text}
        </Notice>
      ) : null}
      {usage && !usage.active ? (
        <Notice tone="info" title="AI review is off">
          Set AI_REVIEW_ENABLED=true and GEMINI_KEY on the backend, and add ai_review to INGESTION_JOB_TYPES.
        </Notice>
      ) : null}
      {usage?.active ? (
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm">
          <span>
            Today <span className="font-medium tabular-nums">{usage.requestsToday}</span> of {usage.dailyBudget} free requests
          </span>
          <span>{usage.reviewedListings.toLocaleString("en-IN")} listings reviewed</span>
          <span>{usage.queuedJobs} reviews queued</span>
          <span>{usage.decidedAutomatically.toLocaleString("en-IN")} decided automatically</span>
          <Button
            size="sm"
            variant="outline"
            disabled={busy !== null}
            onClick={() => void act("queue", () => api("/api/admin/ai-review/queue-guided", { method: "POST" }))}
          >
            Review all guides
          </Button>
        </div>
      ) : null}

      {published !== null ? (
        <div className="flex flex-wrap items-center gap-3 rounded-lg border border-line bg-surface p-3 text-sm">
          <span>
            Readers {published ? <span className="font-medium text-verified">see</span> : <span className="font-medium">do not see</span>} AI
            improvements (short summaries, tags, prices and hours found in the text, In short).
          </span>
          <Button
            size="sm"
            variant={published ? "outline" : "primary"}
            disabled={busy !== null}
            onClick={() =>
              void act("publish", () => api("/api/admin/ai-review/publish", { method: "PUT", body: { published: !published } }))
            }
          >
            {published ? "Hide from readers" : "Show to readers"}
          </Button>
        </div>
      ) : null}

      <form
        className="flex flex-wrap items-end gap-2 text-sm"
        onSubmit={(e) => {
          e.preventDefault();
          if (!previewSlug.trim()) return;
          setMessage(null);
          api<AiPreview>(`/api/admin/ai-review/destinations/${encodeURIComponent(previewSlug.trim())}/preview`)
            .then(setPreview)
            .catch((err) => setMessage({ tone: "error", text: problemMessage(err) }));
        }}
      >
        <label className="grid gap-1">
          <span className="text-muted">Preview a destination (slug)</span>
          <input
            value={previewSlug}
            onChange={(e) => setPreviewSlug(e.target.value)}
            placeholder="new-delhi"
            className="h-9 rounded-md border border-line-strong bg-surface px-2"
          />
        </label>
        <Button size="sm" type="submit" variant="outline">
          Show before and after
        </Button>
      </form>

      {preview ? <PreviewView p={preview} /> : null}

      {usage?.active && usage.models?.length ? (
        <ul className="flex flex-wrap gap-x-5 gap-y-1 text-xs text-muted" aria-label="Requests per model today">
          {usage.models.map((m) => (
            <li key={m.name}>
              <span className="font-medium text-ink-2">{m.name}</span>{" "}
              <span className="tabular-nums">
                {m.requestsToday}/{m.requestsPerDay}
              </span>
            </li>
          ))}
        </ul>
      ) : null}

      {flags && flags.items.length ? (
        <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
          {flags.items.map((f) => (
            <li key={f.id} className="space-y-2 p-3 text-sm">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <p>
                  <span className="font-medium">{f.title}</span>{" "}
                  <span className="text-muted">
                    under {f.section} in{" "}
                    <Link className="underline" href={`/d/${f.destinationSlug}`}>
                      {f.destinationName}
                    </Link>
                    {f.area ? ` · ${f.area}` : ""}
                  </span>
                </p>
                <span className="flex flex-wrap gap-1">
                  {f.issues.map((i) => (
                    <Badge key={i} tone="stale">
                      {ISSUE_TEXT[i] ?? i}
                    </Badge>
                  ))}
                  {!f.fitsSection && !f.issues.includes("wrong_section") ? <Badge tone="stale">wrong section</Badge> : null}
                  {f.inDestination === "no" && !f.issues.includes("outside_destination") ? <Badge tone="stale">not in this destination</Badge> : null}
                </span>
              </div>
              {f.description ? <p className="line-clamp-3 text-ink-2">{f.description}</p> : null}
              <p className="text-muted">
                Reviewer: “{f.reason}” ({Math.round(f.confidence * 100)}% sure
                {f.suggestedSection && f.suggestedSection !== f.section ? `, belongs under ${f.suggestedSection}` : ""})
              </p>
              <div className="flex flex-wrap gap-2">
                {moveTarget(f) ? (
                  <Button size="sm" disabled={busy !== null} onClick={() => void act(f.id, () => api(`/api/admin/ai-review/flags/${f.id}/move`, { method: "POST" }))}>
                    Move to {SECTION_TEXT[moveTarget(f)!]}
                  </Button>
                ) : null}
                <Button
                  size="sm"
                  variant={moveTarget(f) ? "outline" : "primary"}
                  disabled={busy !== null}
                  onClick={() => void act(f.id, () => api(`/api/admin/ai-review/flags/${f.id}/accept`, { method: "POST" }))}
                >
                  Hide listing
                </Button>
                <Button size="sm" variant="outline" disabled={busy !== null} onClick={() => void act(f.id, () => api(`/api/admin/ai-review/flags/${f.id}/dismiss`, { method: "POST" }))}>
                  Keep it
                </Button>
              </div>
            </li>
          ))}
        </ul>
      ) : flags ? (
        <p className="text-sm text-muted">No open flags.</p>
      ) : null}
      {decisions && decisions.items.length ? (
        <details className="rounded-lg border border-line bg-surface p-3 text-sm">
          <summary className="cursor-pointer select-none font-medium">
            Decided automatically ({decisions.totalItems.toLocaleString("en-IN")})
          </summary>
          <ul className="mt-2 divide-y divide-line">
            {decisions.items.map((d) => (
              <li key={d.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
                <span className="min-w-0">
                  <span className="font-medium">{d.title}</span>{" "}
                  <span className="text-muted">
                    in{" "}
                    <Link className="underline" href={`/d/${d.destinationSlug}`}>
                      {d.destinationName}
                    </Link>
                    {": "}
                    {d.resolution === "moved"
                      ? `moved from ${SECTION_TEXT[d.previousSection ?? ""] ?? d.previousSection} to ${SECTION_TEXT[d.section] ?? d.section}`
                      : d.resolution === "hidden"
                        ? "hidden"
                        : d.resolution === "noted"
                          ? "noted, left as is"
                          : "kept"}
                    {" · "}“{d.reason}”
                  </span>
                </span>
                {d.resolution === "moved" || d.resolution === "hidden" ? (
                  <Button size="sm" variant="outline" disabled={busy !== null} onClick={() => void act(d.id, () => api(`/api/admin/ai-review/flags/${d.id}/undo`, { method: "POST" }))}>
                    Undo
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        </details>
      ) : null}

      {checks && checks.items.length ? (
        <details className="rounded-lg border border-line bg-surface p-3 text-sm">
          <summary className="cursor-pointer select-none font-medium">
            Checked against official websites ({checks.totalItems.toLocaleString("en-IN")})
          </summary>
          <ul className="mt-2 divide-y divide-line">
            {checks.items.map((c) => (
              <li key={c.id} className="flex flex-wrap items-start justify-between gap-2 py-2">
                <span className="min-w-0">
                  <span className="font-medium">{c.title}</span>{" "}
                  <span className="text-muted">
                    in{" "}
                    <Link className="underline" href={`/d/${c.destinationSlug}`}>
                      {c.destinationName}
                    </Link>
                    {": "}
                    {c.kind === "price" ? "price" : "hours"} {c.oldValue} — {CHECK_TEXT[c.verdict]}
                    {c.newValue ? ` ${c.newValue}` : ""}
                    {c.outcome === "noted" && (c.verdict === "confirmed" || c.verdict === "changed") ? " (not applied: AI improvements are switched off)" : ""}
                    {c.undoneAt ? " · undone" : ""}
                  </span>
                  {c.quote ? (
                    <span className="mt-0.5 block text-xs text-muted">
                      “{c.quote}”{" "}
                      {c.pageUrl ? (
                        <a className="underline" href={c.pageUrl} target="_blank" rel="noopener noreferrer">
                          page
                        </a>
                      ) : null}
                    </span>
                  ) : null}
                </span>
                {c.outcome === "applied" && !c.undoneAt ? (
                  <Button size="sm" variant="outline" disabled={busy !== null} onClick={() => void act(c.id, () => api(`/api/admin/ai-review/checks/${c.id}/undo`, { method: "POST" }))}>
                    Undo
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        </details>
      ) : null}

      {flags && flags.totalItems > flags.items.length ? (
        <p className="text-sm text-muted">Showing the {flags.items.length} most confident of {flags.totalItems.toLocaleString("en-IN")} open flags.</p>
      ) : null}
    </section>
  );
}

function PreviewView({ p }: { p: AiPreview }) {
  return (
    <div className="space-y-3 rounded-lg border border-line bg-surface p-3 text-sm" aria-label={`Before and after for ${p.name}`}>
      <p>
        <span className="font-medium">{p.name}</span>: {p.enriched} of {p.listings} listings processed, {p.summariesAccepted} summaries
        kept, {p.summariesRejected} rejected by the checks, {p.tagged} tagged, {p.factsExtracted} prices or hours found in text.
      </p>
      {p.inShort.length || p.inShortRejected.length ? (
        <div>
          <p className="font-medium">In short</p>
          <ul className="list-disc pl-5 text-ink-2">
            {p.inShort.map((i) => (
              <li key={i.topic}>{i.text}</li>
            ))}
            {p.inShortRejected.map((i) => (
              <li key={`r-${i.topic}`} className="text-muted line-through" title={i.reason}>
                {i.text} <span className="no-underline">(rejected: {i.reason})</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      <ul className="divide-y divide-line">
        {p.entries
          .filter((e) => e.summaryStatus !== "not_needed" || e.tags.length || e.extracted.length)
          .slice(0, 40)
          .map((e) => (
            <li key={e.entryId} className="space-y-1 py-2">
              <p className="font-medium">
                {e.title} <span className="font-normal text-muted">({e.section})</span>
              </p>
              {e.summary ? (
                <div className="grid gap-2 md:grid-cols-2">
                  <p className="text-muted">
                    <span className="block text-xs uppercase tracking-wide">Original</span>
                    {e.original}
                  </p>
                  <p className={e.summaryStatus === "accepted" ? "text-ink-2" : "text-conflict"}>
                    <span className="block text-xs uppercase tracking-wide">
                      {e.summaryStatus === "accepted" ? "Summary" : `Rejected: ${e.summaryRejection}`}
                    </span>
                    {e.summary}
                  </p>
                </div>
              ) : null}
              {e.tags.length ? <p className="text-muted">Tags: {e.tags.map((t) => `${t.tag} (“${t.quote}”)`).join(", ")}</p> : null}
              {e.extracted.length ? <p className="text-muted">Found in text: {e.extracted.map((f) => `${f.kind}: “${f.quote}”`).join(", ")}</p> : null}
            </li>
          ))}
      </ul>
    </div>
  );
}
