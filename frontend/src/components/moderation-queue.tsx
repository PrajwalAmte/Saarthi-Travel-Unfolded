"use client";

import Link from "next/link";
import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import type { Page, QueueItem } from "@/lib/types";
import { formatDate, safeHref } from "@/lib/utils";
import { useSession } from "./session-provider";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";
import { Field, Select, Textarea } from "./ui/field";
import { Notice } from "./ui/notice";

const TYPE_LABEL: Record<string, string> = {
  correction: "Correction",
  closure_report: "Closure report",
  duplicate_report: "Duplicate report",
  incorrect_report: "Incorrect information",
  new_place: "New place",
  new_tip: "Local tip",
};

function str(v: unknown): string | null {
  return typeof v === "string" && v.length ? v : null;
}

function Details({ item }: { item: QueueItem }) {
  const d = item.details;
  if (item.itemType === "contribution") {
    const payload = (d.payload ?? {}) as Record<string, unknown>;
    return (
      <div className="space-y-2 text-sm">
        <p className="font-medium">
          {TYPE_LABEL[String(d.type)] ?? String(d.type)}
          {str(d.targetTitle) ? <span className="font-normal text-ink-2"> · {String(d.targetTitle)}</span> : null}
        </p>
        {str(d.targetValue) ? (
          <div className="grid gap-2 sm:grid-cols-2">
            <div className="rounded-md bg-sunken/70 p-2">
              <p className="text-xs uppercase tracking-wide text-muted">Current ({String(d.targetKind)})</p>
              <p className="whitespace-pre-line text-ink-2">{String(d.targetValue)}</p>
            </div>
            {str(payload.value) ? (
              <div className="rounded-md bg-accent-soft p-2">
                <p className="text-xs uppercase tracking-wide text-muted">Proposed</p>
                <p className="whitespace-pre-line text-ink">{String(payload.value)}</p>
              </div>
            ) : null}
          </div>
        ) : null}
        <dl className="grid gap-x-4 gap-y-1 sm:grid-cols-[max-content_1fr]">
          {Object.entries(payload)
            .filter(([k]) => k !== "value")
            .map(([k, v]) => (
              <React.Fragment key={k}>
                <dt className="text-muted">{k}</dt>
                <dd className="whitespace-pre-line break-words">{String(v)}</dd>
              </React.Fragment>
            ))}
        </dl>
        {str(d.note) ? <p className="text-ink-2"><span className="text-muted">Note: </span>{String(d.note)}</p> : null}
        {safeHref(str(d.evidenceUrl)) ? (
          <a className="break-all text-accent underline" href={safeHref(str(d.evidenceUrl))!} target="_blank" rel="noopener noreferrer nofollow">
            {String(d.evidenceUrl)}
          </a>
        ) : null}
        {d.conflictOfInterest ? <Notice tone="warning">Conflict of interest declared: {String(d.coiDetails ?? "")}</Notice> : null}
        <p className="text-xs text-muted">
          Submitted by {String(d.submitter ?? "a former contributor")} ({String(d.submitterTier ?? "?")}, {String(d.submitterAccepted ?? 0)} accepted) ·{" "}
          {formatDate(str(d.submittedAt))}
        </p>
      </div>
    );
  }
  if (item.itemType === "verification") {
    return (
      <div className="space-y-2 text-sm">
        <p className="font-medium">
          {d.verdict === "confirm" ? "Confirmation" : "Report that this is no longer true"}
          {str(d.entryTitle) ? <span className="font-normal text-ink-2"> · {String(d.entryTitle)}</span> : null}
        </p>
        <p className="whitespace-pre-line rounded-md bg-sunken/70 p-2 text-ink-2">{String(d.factValue ?? "")}</p>
        {str(d.note) ? <p>{String(d.note)}</p> : null}
        <p className="text-xs text-muted">
          By {String(d.submitter ?? "a former contributor")} · {formatDate(str(d.submittedAt))} ·{" "}
          <Link className="underline" href={`/facts/${String(d.factId)}`}>fact history</Link>
        </p>
      </div>
    );
  }
  return (
    <div className="space-y-1 text-sm">
      <p className="font-medium">Abuse report: {String(d.reason)}</p>
      <p className="text-ink-2">
        Target: {String(d.targetType)} {String(d.targetId)}
      </p>
      {str(d.details) ? <p>{String(d.details)}</p> : null}
      <p className="text-xs text-muted">
        Reported by {String(d.reporter ?? "a former contributor")} · {formatDate(str(d.submittedAt))}
      </p>
    </div>
  );
}

function Decide({ item, onDone }: { item: QueueItem; onDone: () => void }) {
  const [reason, setReason] = React.useState("");
  const [action, setAction] = React.useState("none");
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);
  async function decide(decision: "accept" | "reject") {
    setBusy(true);
    setError(null);
    try {
      await api(`/api/moderation/items/${item.id}/decision`, {
        method: "POST",
        body: { decision, reason: reason || undefined, action: item.itemType === "abuse_flag" ? action : undefined },
      });
      onDone();
    } catch (e) {
      setError(problemMessage(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="mt-3 space-y-3">
      <Field label="Reason (shown to the contributor)" htmlFor={`r-${item.id}`}>
        <Textarea id={`r-${item.id}`} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} className="min-h-14" />
      </Field>
      {item.itemType === "abuse_flag" ? (
        <Field label="Action if upheld" htmlFor={`a-${item.id}`}>
          <Select id={`a-${item.id}`} value={action} onChange={(e) => setAction(e.target.value)}>
            <option value="none">No further action</option>
            <option value="hide_entry">Hide the entry</option>
            <option value="retract_fact">Retract the fact</option>
            <option value="reject_contribution">Reject or revert the contribution</option>
            <option value="suspend_user">Suspend the account (admins)</option>
          </Select>
        </Field>
      ) : null}
      {error ? <Notice tone="error">{error}</Notice> : null}
      <div className="flex gap-2">
        <Button size="sm" disabled={busy} onClick={() => void decide("accept")}>
          {item.itemType === "abuse_flag" ? "Uphold" : "Accept"}
        </Button>
        <Button size="sm" variant="outline" disabled={busy} onClick={() => void decide("reject")}>
          {item.itemType === "abuse_flag" ? "Dismiss" : "Reject"}
        </Button>
      </div>
    </div>
  );
}

export function ModerationQueue() {
  const { session, loading } = useSession();
  const [status, setStatus] = React.useState<"open" | "resolved">("open");
  const [data, setData] = React.useState<Page<QueueItem> | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [page, setPage] = React.useState(0);

  const [version, setVersion] = React.useState(0);
  const u = session?.user;
  const reviewer = Boolean(u && (u.admin || u.tier === "trusted" || u.tier === "steward"));

  React.useEffect(() => {
    if (!reviewer) return;
    let cancelled = false;
    api<Page<QueueItem>>(`/api/moderation/queue?status=${status}&page=${page}`)
      .then((d) => {
        if (cancelled) return;
        setData(d);
        setError(null);
      })
      .catch((e) => {
        if (!cancelled) setError(problemMessage(e));
      });
    return () => {
      cancelled = true;
    };
  }, [reviewer, status, page, version]);

  if (loading) return <div className="skeleton h-64 w-full" />;
  if (!session?.authenticated) {
    return (
      <Notice tone="info" title="Sign in required">
        <Link className="underline" href="/signin?next=/moderation">Sign in</Link> as a trusted contributor or city steward to review submissions.
      </Notice>
    );
  }
  if (!reviewer) {
    return (
      <Notice tone="info" title="Reviewing opens up as you contribute">
        Trusted contributors and city stewards review submissions. You become trusted automatically after enough accepted
        contributions; see your <Link className="underline" href="/account">account</Link>.
      </Notice>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-semibold">Review queue</h1>
          <p className="mt-1 text-sm text-muted">
            {u?.admin ? "As an administrator you see every item." : u?.tier === "steward" ? "You see normal items and high-impact items for your destinations." : "You see normal-impact items. High-impact items need a city steward."}{" "}
            You never see your own submissions.
          </p>
        </div>
        <div className="flex gap-2">
          {(["open", "resolved"] as const).map((s) => (
            <Button key={s} size="sm" variant={status === s ? "primary" : "outline"} onClick={() => { setStatus(s); setPage(0); }}>
              {s === "open" ? "Open" : "Resolved"}
            </Button>
          ))}
        </div>
      </div>
      {error ? <Notice tone="error">{error}</Notice> : null}
      {!data ? (
        <div className="skeleton h-40 w-full" />
      ) : data.items.length === 0 ? (
        <p className="rounded-lg border border-dashed border-line-strong p-8 text-center text-muted">
          {status === "open" ? "Nothing to review right now." : "No resolved items yet."}
        </p>
      ) : (
        <ul className="space-y-4">
          {data.items.map((item) => (
            <li key={item.id} className="rounded-lg border border-line bg-surface p-4">
              <div className="mb-2 flex flex-wrap items-center gap-2 text-xs">
                {item.destinationSlug ? (
                  <Link href={`/d/${item.destinationSlug}`} className="font-medium text-accent underline">
                    {item.destinationName}
                  </Link>
                ) : null}
                <Badge tone={item.requiresRole === "steward" ? "stale" : "neutral"}>
                  {item.requiresRole === "steward" ? "Needs a steward" : "Trusted review"}
                </Badge>
                {item.reasons.map((r) => (
                  <Badge key={r}>{r.replaceAll("_", " ")}</Badge>
                ))}
                <span className="text-muted">queued {formatDate(item.createdAt)}</span>
              </div>
              <Details item={item} />
              {status === "open" ? <Decide item={item} onDone={() => setVersion((v) => v + 1)} /> : <p className="mt-2 text-sm text-muted">Resolution: {item.resolution}</p>}
            </li>
          ))}
        </ul>
      )}
      {data && data.totalPages > 1 ? (
        <div className="flex items-center justify-between text-sm">
          <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
          <span className="text-muted">Page {page + 1} of {data.totalPages}</span>
          <Button size="sm" variant="ghost" disabled={page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</Button>
        </div>
      ) : null}
    </div>
  );
}
