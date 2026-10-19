import { BadgeCheck, ExternalLink } from "lucide-react";
import Link from "next/link";
import type { Fact } from "@/lib/types";
import { formatDate, formatMonth, safeHref } from "@/lib/utils";
import { StillTrue } from "./still-true";

/**
 * A quiet source line under a fact: where it comes from, when, and whether locals confirmed it.
 * Only signals worth acting on are emphasised (confirmations, reported changes, a pending update);
 * the full record, including the status, review window and history, is one click away.
 */
export function FactMeta({ fact, destinationId }: { fact: Fact; destinationId: string; compact?: boolean }) {
  const source = fact.evidence[0];
  const href = source ? safeHref(source.permalink ?? source.url) : null;
  const label = !source
    ? null
    : source.sourceId === "community"
      ? `Shared by ${source.contributorName ?? "a local contributor"}`
      : source.sourceName;
  const when = fact.observedAt
    ? fact.observedBasis === "computed_period"
      ? `data to ${formatDate(fact.observedAt)}`
      : formatMonth(fact.observedAt)
    : null;
  return (
    <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
      {label ? (
        href ? (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer nofollow"
            className="inline-flex items-center gap-1 hover:text-ink hover:underline"
            title={`${source!.licenceName}${source!.sourceRevisionAt ? `, revision of ${formatDate(source!.sourceRevisionAt)}` : ""}`}
          >
            {label}
            {when ? <span>· {when}</span> : null}
            <ExternalLink className="size-3" aria-hidden />
            <span className="sr-only">(opens in a new tab)</span>
          </a>
        ) : (
          <span>
            {label}
            {when ? ` · ${when}` : ""}
          </span>
        )
      ) : null}
      {fact.verificationCount > 0 ? (
        <span className="inline-flex items-center gap-1 text-verified">
          <BadgeCheck className="size-3.5" aria-hidden />
          Confirmed by {fact.verificationCount === 1 ? "a local" : `${fact.verificationCount} locals`}
          {fact.lastVerifiedAt ? ` · ${formatMonth(fact.lastVerifiedAt)}` : ""}
        </span>
      ) : null}
      {fact.verificationStatus === "conflicting_reports" || fact.denialCount > 0 ? (
        <span className="text-conflict">Reported as changed — check before you go</span>
      ) : null}
      {fact.pendingCorrections > 0 ? <span>Update under review</span> : null}
      {fact.ownerSubmitted ? <span>From the owner</span> : null}
      <StillTrue fact={fact} destinationId={destinationId} />
      <Link href={`/facts/${fact.id}`} className="hover:text-ink hover:underline">
        Details
      </Link>
    </div>
  );
}
