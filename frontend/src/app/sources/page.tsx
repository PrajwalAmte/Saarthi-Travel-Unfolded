import Link from "next/link";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { apiGet } from "@/lib/server-api";
import type { IngestionStatus } from "@/lib/types";
import { formatDate, safeHref } from "@/lib/utils";

export const dynamic = "force-dynamic";
export const metadata = { title: "Sources and licences" };

const RUN_STATUS: Record<string, string> = {
  running: "running",
  succeeded: "succeeded",
  succeeded_with_warnings: "succeeded with warnings",
  quality_failed: "imported but held back by the quality gate",
  failed: "failed",
};

function QualityBadge({ status, score }: { status: string; score: number | null }) {
  if (status === "passed") return <Badge tone="verified">Quality checks passed{score !== null ? ` · ${score}` : ""}</Badge>;
  if (status === "warning") return <Badge tone="stale">Quality warnings{score !== null ? ` · ${score}` : ""}</Badge>;
  if (status === "failed") return <Badge tone="conflict">Quality checks failed: not published</Badge>;
  return <Badge>Quality not evaluated</Badge>;
}

export default async function SourcesPage() {
  let status: IngestionStatus | null = null;
  try {
    status = await apiGet<IngestionStatus>("/api/status/ingestion");
  } catch {
    status = null;
  }
  return (
    <div className="container-page max-w-4xl py-10">
      <h1 className="text-3xl font-semibold sm:text-4xl">Sources and licences</h1>
      <p className="mt-3 text-ink-2">
        Every fact in a guide links back to one of these sources. The software is Apache-2.0; the data keeps the licence of the
        source it came from, and contributions are CC BY-SA 4.0. Sync health is shown as recorded by the ingestion jobs; every
        import is checked by a source-specific quality gate, and data from a source whose checks fail is not published.
      </p>
      {!status ? (
        <Notice tone="error" className="mt-6">
          The source registry could not be loaded right now.
        </Notice>
      ) : (
        <>
          <ul className="mt-8 space-y-4">
            {status.sources.map((s) => (
              <li key={s.id} className="rounded-lg border border-line bg-surface p-5">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div>
                    <h2 className="font-serif text-xl font-semibold">{s.name}</h2>
                    <p className="text-sm text-muted">{s.attribution}</p>
                    {s.qualityReportId || s.latestRun ? (
                      <Link href={`/sources/${s.id}`} className="text-sm underline">
                        Quality report and sync runs
                      </Link>
                    ) : null}
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {!s.enabled ? <Badge tone="neutral">Disabled</Badge> : null}
                    {s.lastAttemptFailed ? <Badge tone="conflict">Last sync failed</Badge> : s.lastSuccessAt ? <Badge tone="verified">Syncing</Badge> : <Badge>Not synced yet</Badge>}
                    <QualityBadge status={s.qualityStatus} score={s.qualityScore} />
                  </div>
                </div>
                <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-2">
                  <div>
                    <dt className="text-muted">Licence</dt>
                    <dd>
                      {safeHref(s.licenceUrl) ? (
                        <a className="underline" href={s.licenceUrl!} target="_blank" rel="noopener noreferrer">
                          {s.licenceName}
                        </a>
                      ) : (
                        s.licenceName
                      )}
                      {s.shareAlike ? " (share-alike)" : ""}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted">Currently contributes</dt>
                    <dd>
                      {s.contributions.length ? (
                        <ul>
                          {s.contributions.map((c) => (
                            <li key={c.kind}>
                              {c.count.toLocaleString("en-IN")} <span className="text-muted">{c.label.toLowerCase()}</span>
                            </li>
                          ))}
                        </ul>
                      ) : (
                        "Nothing yet"
                      )}
                    </dd>
                  </div>
                  <div className="sm:col-span-2">
                    <dt className="text-muted">How it is accessed</dt>
                    <dd>{s.accessMethod}</dd>
                  </div>
                  {s.usageNotes ? (
                    <div className="sm:col-span-2">
                      <dt className="text-muted">Use and retention</dt>
                      <dd>{s.usageNotes}</dd>
                    </div>
                  ) : null}
                  <div>
                    <dt className="text-muted">Refresh policy</dt>
                    <dd>Every {s.refreshIntervalDays} days</dd>
                  </div>
                  <div>
                    <dt className="text-muted">Last successful sync</dt>
                    <dd>
                      {formatDate(s.lastSuccessAt) ?? "Never"}
                      {s.nextSyncDueAt ? <span className="text-muted"> · next due {formatDate(s.nextSyncDueAt)}</span> : null}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted">Last published</dt>
                    <dd>{formatDate(s.lastPublicationAt) ?? "Not published"}</dd>
                  </div>
                  {s.datasetVersion ? (
                    <div>
                      <dt className="text-muted">Dataset version</dt>
                      <dd className="break-all">{s.datasetVersion}</dd>
                    </div>
                  ) : null}
                  {s.latestRun ? (
                    <div className="sm:col-span-2">
                      <dt className="text-muted">Latest run ({RUN_STATUS[s.latestRun.status] ?? s.latestRun.status})</dt>
                      <dd className="text-ink-2">
                        Fetched {s.latestRun.fetched.toLocaleString("en-IN")}, parsed {s.latestRun.parsed.toLocaleString("en-IN")},
                        rejected or skipped {s.latestRun.rejected.toLocaleString("en-IN")}; new {s.latestRun.inserted.toLocaleString("en-IN")},
                        changed {s.latestRun.updated.toLocaleString("en-IN")}, unchanged {s.latestRun.unchanged.toLocaleString("en-IN")},
                        removed {s.latestRun.deleted.toLocaleString("en-IN")}; published {s.latestRun.published.toLocaleString("en-IN")}.
                        {s.latestRun.status === "failed" ? ` Stopped at the ${s.latestRun.stage} stage.` : ""}
                        {s.latestRun.warnings.length ? (
                          <ul className="mt-1 list-disc pl-5 text-sm">
                            {s.latestRun.warnings.slice(0, 4).map((w, i) => (
                              <li key={i}>{w}</li>
                            ))}
                          </ul>
                        ) : null}
                      </dd>
                    </div>
                  ) : null}
                </dl>
              </li>
            ))}
          </ul>
          <section className="mt-10">
            <h2 className="text-2xl font-semibold">Ingestion status</h2>
            <p className="mt-2 text-sm text-ink-2">
              Jobs: {Object.entries(status.jobsByStatus).map(([k, v]) => `${v} ${k}`).join(", ") || "none yet"}.
              {status.lastSuccessfulJobAt ? ` Last successful job ${formatDate(status.lastSuccessfulJobAt)}.` : ""}
            </p>
            {status.recentFailures.length ? (
              <ul className="mt-3 space-y-2">
                {status.recentFailures.map((f, i) => (
                  <li key={i} className="rounded-md border border-conflict/20 bg-conflict-soft p-3 text-sm">
                    <span className="font-medium">{f.jobType}</span>
                    {f.target ? ` · ${f.target}` : ""} · {formatDate(f.at) ?? ""}
                    <p className="mt-1 break-words text-ink-2">{f.error}</p>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-2 text-sm text-muted">No recent failures.</p>
            )}
          </section>
        </>
      )}
    </div>
  );
}
