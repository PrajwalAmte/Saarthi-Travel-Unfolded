import Link from "next/link";
import { notFound } from "next/navigation";
import { CoverageBadge } from "@/components/coverage-badge";
import { AutoRefresh } from "@/components/auto-refresh";
import { ChooseCandidate } from "@/components/choose-candidate";
import { NotifyMe } from "@/components/notify-me";
import { Notice } from "@/components/ui/notice";
import { apiGetOrNull } from "@/lib/server-api";
import type { RequestView } from "@/lib/types";
import { formatDate } from "@/lib/utils";
import { NOINDEX } from "@/lib/seo";

export const dynamic = "force-dynamic";
export const metadata = { title: "Request status", robots: NOINDEX };

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
    // A starter guide is finished work: everything the open sources have is in.
    case "building":
    case "published":
      return 3;
  }
}

const WAITING: RequestView["status"][] = ["received", "queued", "processing"];
const CAN_NOTIFY: RequestView["status"][] = ["received", "queued", "processing", "indexed_no_guide", "needs_review"];

export default async function RequestStatusPage({
  params,
  searchParams,
}: {
  params: Promise<{ code: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { code } = await params;
  const emailed = (await searchParams).emailed === "1";
  if (!/^[a-z0-9]{6,16}$/.test(code)) notFound();
  const r = await apiGetOrNull<RequestView>(`/api/requests/${code}`);
  if (!r) notFound();
  const step = stepIndex(r);
  const tone = r.status === "published" || r.status === "building" ? "success" : r.status === "unresolvable" ? "error" : r.status === "needs_review" ? "warning" : "info";
  return (
    <div className="container-page max-w-2xl py-10">
      <p className="text-sm text-muted">Destination request</p>
      <h1 className="mt-1 text-3xl font-semibold sm:text-4xl">“{r.query}”</h1>
      {r.stateHint ? <p className="mt-1 text-ink-2">in {r.stateHint}</p> : null}

      <Notice tone={tone} className="mt-6" title={r.statusLabel} role="status">
        {r.statusDetail}
      </Notice>

      {r.estimate ? (
        <section aria-label="Expected wait" className="mt-6 rounded-lg border border-line bg-surface p-4">
          {r.estimate.importerRunning ? (
            <>
              <p className="text-sm text-muted">Expected wait</p>
              <p className="mt-1 text-xl font-semibold">{r.estimate.summary}</p>
            </>
          ) : (
            <p className="font-medium">{r.estimate.summary}</p>
          )}
          <p className="mt-2 text-sm text-muted">{r.estimate.basis}</p>
        </section>
      ) : null}

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

      {r.emailUpdates && CAN_NOTIFY.includes(r.status) ? (
        <NotifyMe code={r.trackingCode} alreadySubscribed={emailed} noGuideYet={r.status === "indexed_no_guide"} />
      ) : null}

      <AutoRefresh active={WAITING.includes(r.status)} />

      {r.status === "building" && r.destination ? (
        <p className="mt-4 text-sm">
          <Link href={`/d/${r.destination.slug}`} className="text-accent underline">
            Open the starter guide
          </Link>
          {" · "}
          <Link href={`/contribute/${r.destination.slug}`} className="text-accent underline">
            Know {r.destination.name}? Add a place
          </Link>
        </p>
      ) : null}

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
