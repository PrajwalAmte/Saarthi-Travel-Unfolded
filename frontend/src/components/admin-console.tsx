"use client";

import Link from "next/link";
import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import type { IngestionStatus, JobView, Page } from "@/lib/types";
import { formatDate } from "@/lib/utils";
import { AiReviewPanel } from "./ai-review-panel";
import { useSession } from "./session-provider";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";
import { Field, Input, Select } from "./ui/field";
import { Notice } from "./ui/notice";

const STATUS_TONE: Record<string, "neutral" | "verified" | "stale" | "conflict" | "accent"> = {
  queued: "neutral",
  running: "accent",
  succeeded: "verified",
  failed: "conflict",
  cancelled: "neutral",
};

/** A queued job whose note starts with "Waiting:" is paused on purpose (daily quota, rate limit), not failing. */
export function isWaiting(j: Pick<JobView, "status" | "lastError">): boolean {
  return j.status === "queued" && Boolean(j.lastError?.startsWith("Waiting:"));
}

type UserRow = { id: string; displayName: string; email: string | null; tier: string; admin: boolean; status: string; createdAt: string };
type MatchItem = {
  id: string;
  score: number;
  recordSource: string;
  recordExternalId: string;
  recordName: string;
  recordCategory: string;
  candidateName: string;
  candidateCategory: string;
  distanceM: number | null;
  candidateSources: string | null;
};
type RequestRow = { id: string; trackingCode: string; queryText: string; status: string; demandCount: number; lastRequestedAt: string };

/** Operations console for administrators: ingestion jobs, sources, stewards and requests. */
export function AdminConsole() {
  const { session, loading } = useSession();
  const [jobs, setJobs] = React.useState<Page<JobView> | null>(null);
  const [status, setStatus] = React.useState<IngestionStatus | null>(null);
  const [filter, setFilter] = React.useState("");
  const [message, setMessage] = React.useState<{ tone: "success" | "error"; text: string } | null>(null);
  const [users, setUsers] = React.useState<UserRow[]>([]);
  const [requests, setRequests] = React.useState<RequestRow[]>([]);
  const [matches, setMatches] = React.useState<{ total: number; items: MatchItem[] } | null>(null);

  const [version, setVersion] = React.useState(0);
  const isAdmin = Boolean(session?.user?.admin);

  React.useEffect(() => {
    if (!isAdmin) return;
    let cancelled = false;
    Promise.all([
      api<Page<JobView>>(`/api/admin/ingestion/jobs?size=30${filter ? `&status=${filter}` : ""}`),
      api<IngestionStatus>("/api/status/ingestion"),
      api<RequestRow[]>("/api/admin/requests"),
      api<{ total: number; items: MatchItem[] }>("/api/admin/poi-matches?limit=20"),
    ])
      .then(([j, s, r, m]) => {
        if (cancelled) return;
        setJobs(j);
        setStatus(s);
        setRequests(r);
        setMatches(m);
      })
      .catch((e) => {
        if (!cancelled) setMessage({ tone: "error", text: problemMessage(e) });
      });
    return () => {
      cancelled = true;
    };
  }, [isAdmin, filter, version]);

  if (loading) return <div className="skeleton h-64 w-full" />;
  if (!isAdmin) {
    return (
      <Notice tone="info" title="Administrators only">
        This console is for the people operating this instance. Administrators are configured with APP_ADMIN_EMAILS.{" "}
        <Link className="underline" href="/sources">Public source status</Link> is available to everyone.
      </Notice>
    );
  }

  async function act(fn: () => Promise<unknown>, success: string) {
    setMessage(null);
    try {
      await fn();
      setMessage({ tone: "success", text: success });
      setVersion((v) => v + 1);
    } catch (e) {
      setMessage({ tone: "error", text: problemMessage(e) });
    }
  }

  return (
    <div className="space-y-10">
      <div>
        <h1 className="text-3xl font-semibold">Operations</h1>
        <p className="mt-1 text-sm text-muted">Ingestion runs one job at a time in the background. Re-queueing an identical job is a no-op.</p>
      </div>
      {message ? <Notice tone={message.tone} role="status">{message.text}</Notice> : null}

      <section className="space-y-4">
        <h2 className="text-2xl font-semibold">Queue a job</h2>
        <form
          className="space-y-4 rounded-lg border border-line bg-surface p-4"
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void act(
              () =>
                api("/api/admin/ingestion/jobs", {
                  method: "POST",
                  body: {
                    type: f.get("type"),
                    placeSlug: String(f.get("slug") ?? "").trim() || undefined,
                    sourceId: String(f.get("sourceId") ?? "").trim() || undefined,
                    force: f.get("force") === "on",
                  },
                }),
              "Job queued.",
            );
          }}
        >
          <div className="grid gap-3 sm:grid-cols-3 sm:items-start">
            <Field label="Job" htmlFor="job-type">
              <Select id="job-type" name="type" defaultValue="wikivoyage_guide">
                <option value="wikivoyage_guide">Ingest Wikivoyage guide</option>
                <option value="climate">Climate averages</option>
                <option value="place_image">Destination image</option>
                <option value="wikidata_index">Nationwide Wikidata index</option>
                <option value="osm_extract">OpenStreetMap India extract</option>
                <option value="overture_places">Overture places release</option>
                <option value="heritage_monuments">ASI-numbered monuments</option>
                <option value="quality_evaluation">Re-evaluate a source&apos;s quality</option>
                <option value="coverage_refresh">Recompute coverage</option>
              </Select>
            </Field>
            <Field label="Destination slug" htmlFor="job-slug" hint="e.g. mysore (not needed for the index)">
              <Input id="job-slug" name="slug" />
            </Field>
            <Field label="Source id" htmlFor="job-source" hint="only for quality evaluation, e.g. overture-places">
              <Input id="job-source" name="sourceId" />
            </Field>
          </div>
          <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
            <Button type="submit">Queue</Button>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" name="force" /> Force <span className="text-muted">(re-import even if the source is unchanged)</span>
            </label>
          </div>
        </form>
        <Button variant="outline" onClick={() => void act(() => api("/api/admin/ingestion/plan", { method: "POST" }), "Planner ran.")}>
          Run the refresh planner now
        </Button>
      </section>

      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-2xl font-semibold">Jobs</h2>
          <Select aria-label="Filter jobs by status" className="w-40" value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="">All statuses</option>
            <option value="queued">Queued</option>
            <option value="running">Running</option>
            <option value="succeeded">Succeeded</option>
            <option value="failed">Failed</option>
          </Select>
        </div>
        {!jobs ? (
          <div className="skeleton h-40 w-full" />
        ) : (
          <div className="overflow-x-auto rounded-lg border border-line">
            <table className="w-full min-w-[48rem] text-left text-sm">
              <thead className="bg-sunken/80 text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th className="px-3 py-2">Job</th>
                  <th className="px-3 py-2">Target</th>
                  <th className="px-3 py-2">Status</th>
                  <th className="px-3 py-2">Attempts</th>
                  <th className="px-3 py-2">Created</th>
                  <th className="px-3 py-2">Result</th>
                  <th className="px-3 py-2"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {jobs.items.map((j) => (
                  <tr key={j.id} className="border-t border-line align-top">
                    <td className="px-3 py-2 font-medium">{j.jobType}</td>
                    <td className="px-3 py-2">{j.targetPlaceSlug ? <Link className="underline" href={`/d/${j.targetPlaceSlug}`}>{j.targetPlaceName}</Link> : (j.params.wikidataId as string | undefined) ?? "—"}</td>
                    <td className="px-3 py-2"><Badge tone={STATUS_TONE[j.status] ?? "neutral"}>{isWaiting(j) ? "waiting" : j.status}</Badge></td>
                    <td className="px-3 py-2 tabular-nums">{j.attempts}/{j.maxAttempts}</td>
                    <td className="px-3 py-2 text-xs text-muted">{formatDate(j.createdAt)}</td>
                    <td className="max-w-md px-3 py-2 text-xs">
                      {j.lastError ? (
                        <span className={isWaiting(j) ? "break-words text-muted" : "break-words text-conflict"}>{j.lastError}</span>
                      ) : (
                        <code className="break-words text-muted">{JSON.stringify(j.stats).slice(0, 220)}</code>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      {j.status === "failed" || j.status === "cancelled" ? (
                        <Button size="sm" variant="outline" onClick={() => void act(() => api(`/api/admin/ingestion/jobs/${j.id}/retry`, { method: "POST" }), "Job re-queued.")}>Retry</Button>
                      ) : j.status === "queued" ? (
                        <Button size="sm" variant="ghost" onClick={() => void act(() => api(`/api/admin/ingestion/jobs/${j.id}/cancel`, { method: "POST" }), "Job cancelled.")}>Cancel</Button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {status ? (
        <section className="space-y-3">
          <h2 className="text-2xl font-semibold">Sources</h2>
          <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
            {status.sources.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center justify-between gap-3 p-3 text-sm">
                <div>
                  <p className="font-medium">{s.name}</p>
                  <p className="text-xs text-muted">Last success {formatDate(s.lastSuccessAt) ?? "never"} · {s.factsBacked} facts</p>
                </div>
                <Button
                  size="sm"
                  variant={s.enabled ? "outline" : "primary"}
                  onClick={() => void act(() => api(`/api/admin/sources/${s.id}`, { method: "PATCH", body: { enabled: !s.enabled } }), `${s.name} ${s.enabled ? "disabled" : "enabled"}.`)}
                >
                  {s.enabled ? "Disable" : "Enable"}
                </Button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <section className="space-y-3">
        <h2 className="text-2xl font-semibold">Stewards</h2>
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={async (e) => {
            e.preventDefault();
            const q = String(new FormData(e.currentTarget).get("q") ?? "");
            try {
              setUsers(await api<UserRow[]>(`/api/admin/users?q=${encodeURIComponent(q)}`));
            } catch (err) {
              setMessage({ tone: "error", text: problemMessage(err) });
            }
          }}
        >
          <Field label="Find contributor" htmlFor="u-q">
            <Input id="u-q" name="q" placeholder="Name or email" />
          </Field>
          <Button type="submit" variant="outline">Search</Button>
        </form>
        {users.length ? (
          <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
            {users.map((u) => (
              <li key={u.id} className="flex flex-wrap items-center justify-between gap-3 p-3 text-sm">
                <div>
                  <p className="font-medium">{u.displayName} {u.admin ? <Badge tone="accent">admin</Badge> : null}</p>
                  <p className="text-xs text-muted">{u.email ?? "no email"} · {u.tier} · {u.status}</p>
                </div>
                <form
                  className="flex items-center gap-2"
                  onSubmit={(e) => {
                    e.preventDefault();
                    const slug = String(new FormData(e.currentTarget).get("slug") ?? "").trim();
                    void act(() => api("/api/admin/stewards", { method: "POST", body: { userId: u.id, placeSlug: slug } }), `${u.displayName} is now a steward for ${slug}.`);
                  }}
                >
                  <Input name="slug" placeholder="destination slug" className="h-8 w-40" aria-label={`Destination slug for ${u.displayName}`} />
                  <Button size="sm" type="submit">Make steward</Button>
                </form>
              </li>
            ))}
          </ul>
        ) : null}
      </section>

      <AiReviewPanel />

      <section className="space-y-3">
        <h2 className="text-2xl font-semibold">Uncertain place matches</h2>
        <p className="text-sm text-muted">
          A record from one map source resembles a place already known, but not closely enough to merge automatically.
          {matches ? ` ${matches.total.toLocaleString("en-IN")} waiting.` : ""}
        </p>
        {matches && matches.items.length ? (
          <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
            {matches.items.map((m) => (
              <li key={m.id} className="flex flex-wrap items-center justify-between gap-3 p-3 text-sm">
                <div className="min-w-0">
                  <p>
                    <span className="font-medium">{m.recordName}</span>{" "}
                    <span className="text-muted">
                      ({m.recordSource}, {m.recordCategory.replaceAll("_", " ")})
                    </span>
                  </p>
                  <p className="text-ink-2">
                    vs <span className="font-medium">{m.candidateName}</span>{" "}
                    <span className="text-muted">
                      ({m.candidateSources ?? "?"}, {m.candidateCategory.replaceAll("_", " ")}
                      {m.distanceM !== null ? `, ${Math.round(m.distanceM)} m apart` : ""})
                    </span>
                  </p>
                </div>
                <div className="flex gap-2">
                  <Button size="sm" onClick={() => void act(() => api(`/api/admin/poi-matches/${m.id}`, { method: "POST", body: { decision: "same" } }), "Marked as the same place.")}>
                    Same place
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => void act(() => api(`/api/admin/poi-matches/${m.id}`, { method: "POST", body: { decision: "distinct" } }), "Marked as different places.")}>
                    Different
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted">Nothing to review.</p>
        )}
      </section>

      <section className="space-y-3">
        <h2 className="text-2xl font-semibold">Destination requests by demand</h2>
        {requests.length === 0 ? (
          <p className="text-sm text-muted">No requests yet.</p>
        ) : (
          <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
            {requests.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center justify-between gap-2 p-3 text-sm">
                <Link className="font-medium underline" href={`/requests/${r.trackingCode}`}>“{r.queryText}”</Link>
                <span className="text-muted">{r.demandCount} request{r.demandCount === 1 ? "" : "s"} · {r.status} · {formatDate(r.lastRequestedAt)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
