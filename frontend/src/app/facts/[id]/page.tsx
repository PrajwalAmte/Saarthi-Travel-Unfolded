import Link from "next/link";
import { notFound } from "next/navigation";
import { FactMeta } from "@/components/guide/fact-meta";
import { STATUS_META, VOLATILITY_LABEL } from "@/components/guide/fact-status";
import { Badge } from "@/components/ui/badge";
import { apiGetOrNull } from "@/lib/server-api";
import type { FactDetail } from "@/lib/types";
import { formatDate, safeHref } from "@/lib/utils";

export const dynamic = "force-dynamic";
export const metadata = { title: "Fact details" };

const CHANGE_LABEL: Record<string, string> = {
  initial: "First recorded",
  source_update: "Updated in source",
  community_correction: "Corrected by a contributor",
  closure_report: "Closure reported",
  reverted: "Reverted",
};

const METHOD_LABEL: Record<string, string> = {
  wikivoyage_listing_parser: "Parsed from a Wikivoyage listing",
  wikivoyage_section_parser: "Parsed from a Wikivoyage section",
  open_meteo_monthly_aggregation: "Aggregated from daily reanalysis data",
  community_submission: "Submitted by a contributor",
};

export default async function FactPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!/^[0-9a-f-]{36}$/i.test(id)) notFound();
  const data = await apiGetOrNull<FactDetail>(`/api/facts/${id}`);
  if (!data) notFound();
  const { fact } = data;
  const status = STATUS_META[fact.verificationStatus];
  return (
    <div className="container-page max-w-3xl py-10">
      <p className="text-sm text-muted">
        <Link href={`/d/${data.destination.slug}`} className="underline">
          {data.destination.name}
        </Link>
        {data.entry ? ` · ${data.entry.title}` : ""}
      </p>
      <h1 className="mt-1 text-3xl font-semibold">{fact.heading ?? (data.entry ? `${data.entry.title}: ${fact.kind.replace("_", " ")}` : "Fact")}</h1>
      {data.factStatus !== "published" ? (
        <p className="mt-2">
          <Badge tone="stale">This fact is no longer current ({data.factStatus})</Badge>
        </p>
      ) : null}
      <p className="mt-4 whitespace-pre-line text-lg leading-relaxed text-ink-2">{fact.text}</p>
      <FactMeta fact={fact} destinationId={data.destination.id} />

      <dl className="mt-8 grid gap-4 rounded-lg border border-line bg-surface p-5 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-muted">Status</dt>
          <dd className="font-medium">{status.label}</dd>
          <dd className="text-xs text-muted">{status.help}</dd>
        </div>
        <div>
          <dt className="text-muted">Type</dt>
          <dd className="font-medium">{VOLATILITY_LABEL[fact.volatility]}</dd>
          <dd className="text-xs text-muted">Reviewed every {data.reviewWindowDays} days</dd>
        </div>
        <div>
          <dt className="text-muted">Observed</dt>
          <dd className="font-medium">{formatDate(fact.observedAt) ?? "Unknown"}</dd>
        </div>
        <div>
          <dt className="text-muted">Due for review</dt>
          <dd className="font-medium">{fact.reviewDueAt ? formatDate(fact.reviewDueAt) : "Now (no observation date)"}</dd>
        </div>
        <div>
          <dt className="text-muted">Last confirmed by a contributor</dt>
          <dd className="font-medium">{formatDate(fact.lastVerifiedAt) ?? "Never"}</dd>
        </div>
        <div>
          <dt className="text-muted">Source last fetched</dt>
          <dd className="font-medium">{formatDate(fact.sourceRefreshedAt) ?? "—"}</dd>
          <dd className="text-xs text-muted">Fetching a source again does not count as verification.</dd>
        </div>
      </dl>

      <section className="mt-10">
        <h2 className="text-2xl font-semibold">Evidence</h2>
        <ul className="mt-3 space-y-3">
          {fact.evidence.map((ev, i) => {
            const href = safeHref(ev.permalink ?? ev.url);
            return (
              <li key={i} className="rounded-md border border-line p-4 text-sm">
                <p className="font-medium">
                  {ev.sourceId === "community" ? `Contributor report${ev.contributorName ? ` by ${ev.contributorName}` : ""}` : ev.title ?? ev.sourceName}
                </p>
                <p className="text-muted">
                  {METHOD_LABEL[ev.extractionMethod] ?? ev.extractionMethod} · {ev.licenceName}
                  {ev.sourceRevisionAt ? ` · revision of ${formatDate(ev.sourceRevisionAt)}` : ""} · retrieved {formatDate(ev.retrievedAt)}
                </p>
                {href ? (
                  <a href={href} className="mt-1 inline-block break-all text-accent underline" target="_blank" rel="noopener noreferrer nofollow">
                    {href}
                  </a>
                ) : null}
                {ev.excerpt && ev.excerpt !== fact.text ? <p className="mt-2 border-l-2 border-line pl-3 text-ink-2">{ev.excerpt}</p> : null}
              </li>
            );
          })}
        </ul>
      </section>

      <section className="mt-10">
        <h2 className="text-2xl font-semibold">Community verification</h2>
        {data.verifications.length === 0 ? (
          <p className="mt-2 text-ink-2">No contributor has confirmed or disputed this yet.</p>
        ) : (
          <ul className="mt-3 divide-y divide-line rounded-md border border-line">
            {data.verifications.map((v) => (
              <li key={v.id} className="flex flex-wrap items-start justify-between gap-2 p-3 text-sm">
                <div>
                  <p className="font-medium">{v.verdict === "confirm" ? "Confirmed still true" : "Reported not true"}</p>
                  <p className="text-muted">
                    {v.contributor} · {formatDate(v.createdAt)}
                  </p>
                  {v.note ? <p className="mt-1 text-ink-2">{v.note}</p> : null}
                </div>
                <Badge tone={v.status === "accepted" ? "verified" : "stale"}>{v.status === "accepted" ? "Counted" : "Awaiting a second contributor"}</Badge>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="mt-10">
        <h2 className="text-2xl font-semibold">History</h2>
        {data.history.length === 0 ? (
          <p className="mt-2 text-ink-2">No recorded changes.</p>
        ) : (
          <ol className="mt-3 space-y-3 border-l border-line pl-4">
            {data.history.map((h, i) => (
              <li key={i} className="text-sm">
                <p className="font-medium">
                  {CHANGE_LABEL[h.changeType] ?? h.changeType} <span className="font-normal text-muted">· recorded {formatDate(h.recordedAt)}</span>
                </p>
                <p className="text-ink-2">{h.value}</p>
                {h.observedAt ? <p className="text-xs text-muted">Observed {formatDate(h.observedAt)}</p> : null}
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}
