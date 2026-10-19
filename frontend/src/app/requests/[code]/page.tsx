import Link from "next/link";
import { notFound } from "next/navigation";
import { CoverageBadge } from "@/components/coverage-badge";
import { ChooseCandidate } from "@/components/choose-candidate";
import { Notice } from "@/components/ui/notice";
import { apiGetOrNull } from "@/lib/server-api";
import type { RequestView } from "@/lib/types";
import { formatDate } from "@/lib/utils";

export const dynamic = "force-dynamic";
export const metadata = { title: "Request status" };

const STEPS = ["Received", "Located in the index", "Content ingested", "Quality checked"];

function stepIndex(r: RequestView): number {
  switch (r.status) {
    case "received":
    case "queued":
    case "needs_review":
    case "unresolvable":
      return 0;
    case "processing":
    case "indexed_no_guide":
      return 1;
    case "building":
      return 2;
    case "published":
      return 3;
  }
}

export default async function RequestStatusPage({ params }: { params: Promise<{ code: string }> }) {
  const { code } = await params;
  if (!/^[a-z0-9]{6,16}$/.test(code)) notFound();
  const r = await apiGetOrNull<RequestView>(`/api/requests/${code}`);
  if (!r) notFound();
  const step = stepIndex(r);
  const tone = r.status === "published" ? "success" : r.status === "unresolvable" ? "error" : r.status === "needs_review" ? "warning" : "info";
  return (
    <div className="container-page max-w-2xl py-10">
      <p className="text-sm text-muted">Destination request</p>
      <h1 className="mt-1 text-3xl font-semibold sm:text-4xl">“{r.query}”</h1>
      {r.stateHint ? <p className="mt-1 text-ink-2">in {r.stateHint}</p> : null}

      <Notice tone={tone} className="mt-6" title={r.statusLabel} role="status">
        {r.statusDetail}
      </Notice>

      <ol className="mt-6 grid gap-2 sm:grid-cols-4" aria-label="Progress">
        {STEPS.map((s, i) => (
          <li
            key={s}
            className={`rounded-md border px-3 py-2 text-sm ${i <= step && r.status !== "unresolvable" ? "border-accent bg-accent-soft text-accent" : "border-line text-muted"}`}
            aria-current={i === step ? "step" : undefined}
          >
            {s}
          </li>
        ))}
      </ol>

      {r.status === "needs_review" && r.candidates.length ? <ChooseCandidate code={r.trackingCode} candidates={r.candidates} /> : null}

      {r.destination ? (
        <div className="mt-6 flex flex-wrap items-center gap-3 rounded-lg border border-line bg-surface p-4">
          <Link href={`/d/${r.destination.slug}`} className="font-serif text-xl font-semibold text-accent underline">
            {r.destination.name}
          </Link>
          <CoverageBadge status={r.destination.coverageStatus} />
        </div>
      ) : null}

      <dl className="mt-6 grid grid-cols-2 gap-3 text-sm">
        <div>
          <dt className="text-muted">Times requested</dt>
          <dd className="font-medium">{r.demandCount}</dd>
        </div>
        <div>
          <dt className="text-muted">First requested</dt>
          <dd className="font-medium">{formatDate(r.firstRequestedAt)}</dd>
        </div>
      </dl>
      <p className="mt-6 text-sm text-muted">
        Bookmark this page to check progress. Tracking code: <code className="rounded bg-sunken px-1">{r.trackingCode}</code>
      </p>
    </div>
  );
}
