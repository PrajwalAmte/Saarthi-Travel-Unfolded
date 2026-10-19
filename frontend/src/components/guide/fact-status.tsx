import { AlertTriangle, BadgeCheck, FileText, RefreshCw, Users } from "lucide-react";
import type { Fact, VerificationStatus, Volatility } from "@/lib/types";
import { formatDate, formatMonth } from "@/lib/utils";
import { Badge } from "../ui/badge";

export const STATUS_META: Record<
  VerificationStatus,
  { label: string; tone: "source" | "verified" | "community" | "stale" | "conflict"; Icon: typeof FileText; help: string }
> = {
  source_backed: {
    label: "Source-backed",
    tone: "source",
    Icon: FileText,
    help: "Taken from a cited source. Not yet checked by a contributor.",
  },
  community_verified: {
    label: "Community-verified",
    tone: "verified",
    Icon: BadgeCheck,
    help: "Confirmed by at least one contributor within its review window.",
  },
  community_reported: {
    label: "Local contribution",
    tone: "community",
    Icon: Users,
    help: "Reported by a contributor and approved through moderation.",
  },
  needs_reverification: {
    label: "Needs re-verification",
    tone: "stale",
    Icon: RefreshCw,
    help: "Its last observation is older than the review window for this kind of fact, or its date is unknown.",
  },
  conflicting_reports: {
    label: "Conflicting reports",
    tone: "conflict",
    Icon: AlertTriangle,
    help: "A contributor has reported that this is no longer true. Check before relying on it.",
  },
};

export const VOLATILITY_LABEL: Record<Volatility, string> = {
  stable: "Stable fact",
  semi_stable: "Semi-stable",
  volatile: "Volatile",
};

export function StatusPill({ status }: { status: VerificationStatus }) {
  const m = STATUS_META[status];
  return (
    <Badge tone={m.tone} title={m.help}>
      <m.Icon aria-hidden />
      {m.label}
    </Badge>
  );
}

/** Human description of when a fact was observed and verified. */
export function observedText(f: Fact): string {
  const parts: string[] = [];
  if (f.observedAt) {
    const when = f.observedBasis === "computed_period" ? formatDate(f.observedAt) : formatMonth(f.observedAt);
    parts.push(
      f.observedBasis === "source_listing_date"
        ? `Listing last updated ${when}`
        : f.observedBasis === "community_report"
          ? `Reported ${when}`
          : f.observedBasis === "computed_period"
            ? `Data to ${when}`
            : `Observed ${when}`,
    );
  } else {
    parts.push("Observation date unknown");
  }
  if (f.lastVerifiedAt) {
    parts.push(`last confirmed ${formatDate(f.lastVerifiedAt)}`);
  }
  return parts.join(" · ");
}
