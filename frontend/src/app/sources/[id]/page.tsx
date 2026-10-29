import Link from "next/link";
import { notFound } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { apiGetOrNull } from "@/lib/server-api";
import { formatDate } from "@/lib/utils";

export const dynamic = "force-dynamic";

type Check = {
  key: string;
  dimension: string;
  severity: "critical" | "warning" | "info";
  description: string;
  comparator: string | null;
  threshold: number | null;
  actual: number;
  passed: boolean;
  detail: string | null;
};

type Report = {
  id: string;
  sourceId: string;
  scope: string;
  generatedAt: string;
  status: "passed" | "warning" | "failed";
  score: number | null;
  scoreComponents: Record<string, number>;
  metrics: Record<string, unknown>;
  checks: Check[];
  samples: Record<string, unknown>[];
  limitations: string[];
};

type Run = {
  id: string;
  scope: string;
  status: string;
  stage: string;
  startedAt: string;
  finishedAt: string | null;
  fetched: number;
  parsed: number;
  rejected: number;
  inserted: number;
  updated: number;
  unchanged: number;
  deleted: number;
  published: number;
};

type Props = { params: Promise<{ id: string }> };

export async function generateMetadata({ params }: Props) {
  const { id } = await params;
  return { title: `Quality report: ${id}`, alternates: { canonical: `/sources/${id}` } };
}

function fmt(n: unknown) {
  return typeof n === "number" ? n.toLocaleString("en-IN") : String(n ?? "");
}

export default async function SourceQualityPage({ params }: Props) {
  const { id } = await params;
  if (!/^[a-z0-9-]{2,40}$/.test(id)) notFound();
  let report: Report | null = null;
  let runs: Run[] | null = null;
  try {
    [report, runs] = await Promise.all([
      apiGetOrNull<Report>(`/api/sources/${id}/quality`),
      apiGetOrNull<Run[]>(`/api/sources/${id}/runs?limit=10`),
    ]);
  } catch {
    return (
      <div className="container-page max-w-4xl py-10">
        <Notice tone="error">The quality report could not be loaded right now.</Notice>
      </div>
    );
  }
  if (!report && !runs?.length) notFound();
  const metrics = report ? Object.entries(report.metrics).filter(([, v]) => typeof v !== "object" || v === null) : [];

  return (
    <div className="container-page max-w-5xl py-10">
      <p className="text-sm">
        <Link href="/sources" className="underline">
          Sources and licences
        </Link>
      </p>
      <h1 className="mt-2 text-3xl font-semibold">Data quality: {id}</h1>
      {report ? (
        <>
          <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
            <Badge tone={report.status === "passed" ? "verified" : report.status === "warning" ? "stale" : "conflict"}>
              {report.status === "passed" ? "Passed" : report.status === "warning" ? "Passed with warnings" : "Failed: not published"}
            </Badge>
            {report.score !== null ? <span>Score {report.score}</span> : null}
            <span className="text-muted">Evaluated {formatDate(report.generatedAt)} · scope {report.scope}</span>
          </div>
          <p className="mt-3 text-sm text-ink-2">
            Every check is measured on the stored data against a threshold set for this source. Critical checks block
            publication; warnings are shown here but do not. The score is the mean of the components listed below and is
            not adjusted to meet any threshold.
          </p>

          <section className="mt-8">
            <h2 className="text-2xl font-semibold">Checks</h2>
            <div className="mt-3 overflow-x-auto">
              <table className="w-full min-w-[40rem] text-left text-sm">
                <thead className="text-muted">
                  <tr>
                    <th className="py-2 pr-3 font-medium">Check</th>
                    <th className="py-2 pr-3 font-medium">Measured</th>
                    <th className="py-2 pr-3 font-medium">Threshold</th>
                    <th className="py-2 pr-3 font-medium">Severity</th>
                    <th className="py-2 font-medium">Result</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {report.checks.map((c) => (
                    <tr key={c.key}>
                      <td className="py-2 pr-3">
                        {c.description}
                        {c.detail ? <span className="block text-xs text-muted">{c.detail}</span> : null}
                      </td>
                      <td className="py-2 pr-3 tabular-nums">{fmt(c.actual)}</td>
                      <td className="py-2 pr-3 tabular-nums">{c.comparator ? `${c.comparator} ${fmt(c.threshold)}` : "—"}</td>
                      <td className="py-2 pr-3">{c.severity}</td>
                      <td className="py-2">
                        {c.severity === "info" ? "—" : c.passed ? <Badge tone="verified">pass</Badge> : <Badge tone="conflict">fail</Badge>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          {Object.keys(report.scoreComponents).length ? (
            <section className="mt-8">
              <h2 className="text-2xl font-semibold">Score components (%)</h2>
              <ul className="mt-2 grid gap-1 text-sm sm:grid-cols-2">
                {Object.entries(report.scoreComponents).map(([k, v]) => (
                  <li key={k}>
                    <span className="text-muted">{k}</span> {fmt(v)}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {metrics.length ? (
            <section className="mt-8">
              <h2 className="text-2xl font-semibold">Metrics</h2>
              <ul className="mt-2 grid gap-1 text-sm sm:grid-cols-2">
                {metrics.map(([k, v]) => (
                  <li key={k}>
                    <span className="text-muted">{k}</span> {fmt(v)}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          {report.samples.length ? (
            <section className="mt-8">
              <h2 className="text-2xl font-semibold">Sample records for manual inspection</h2>
              <div className="mt-3 overflow-x-auto">
                <table className="w-full min-w-[48rem] text-left text-xs">
                  <thead className="text-muted">
                    <tr>
                      {Object.keys(report.samples[0] ?? {}).map((k) => (
                        <th key={k} className="py-2 pr-3 font-medium">
                          {k}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {report.samples.map((row, i) => (
                      <tr key={i}>
                        {Object.keys(report!.samples[0] ?? {}).map((k) => (
                          <td key={k} className="max-w-[18rem] break-words py-2 pr-3 align-top">
                            {fmt(row[k])}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          ) : null}

          {report.limitations.length ? (
            <section className="mt-8">
              <h2 className="text-2xl font-semibold">Known limitations</h2>
              <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-ink-2">
                {report.limitations.map((l) => (
                  <li key={l}>{l}</li>
                ))}
              </ul>
            </section>
          ) : null}
        </>
      ) : (
        <Notice tone="info" className="mt-4">
          This source has not been evaluated yet.
        </Notice>
      )}

      {runs?.length ? (
        <section className="mt-10">
          <h2 className="text-2xl font-semibold">Recent sync runs</h2>
          <div className="mt-3 overflow-x-auto">
            <table className="w-full min-w-[48rem] text-left text-sm">
              <thead className="text-muted">
                <tr>
                  <th className="py-2 pr-3 font-medium">Started</th>
                  <th className="py-2 pr-3 font-medium">Scope</th>
                  <th className="py-2 pr-3 font-medium">Status</th>
                  <th className="py-2 pr-3 font-medium">Fetched</th>
                  <th className="py-2 pr-3 font-medium">Rejected</th>
                  <th className="py-2 pr-3 font-medium">New / changed / unchanged / removed</th>
                  <th className="py-2 font-medium">Published</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line tabular-nums">
                {runs.map((r) => (
                  <tr key={r.id}>
                    <td className="py-2 pr-3">{formatDate(r.startedAt)}</td>
                    <td className="py-2 pr-3">{r.scope}</td>
                    <td className="py-2 pr-3">
                      {r.status.replaceAll("_", " ")}
                      {r.status === "failed" ? ` (${r.stage})` : ""}
                    </td>
                    <td className="py-2 pr-3">{fmt(r.fetched)}</td>
                    <td className="py-2 pr-3">{fmt(r.rejected)}</td>
                    <td className="py-2 pr-3">
                      {fmt(r.inserted)} / {fmt(r.updated)} / {fmt(r.unchanged)} / {fmt(r.deleted)}
                    </td>
                    <td className="py-2">{fmt(r.published)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ) : null}
    </div>
  );
}
