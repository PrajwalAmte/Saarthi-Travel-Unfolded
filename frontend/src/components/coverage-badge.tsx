import { CircleDashed, CircleDot, Hammer, ShieldCheck } from "lucide-react";
import type { CoverageStatus } from "@/lib/types";
import { Badge } from "./ui/badge";

// Labels say what a traveller gets, not the internal review state.
const META: Record<CoverageStatus, { label: string; tone: "neutral" | "accent" | "verified" | "stale" | "source"; Icon: typeof CircleDot }> = {
  indexed: { label: "No guide yet", tone: "neutral", Icon: CircleDashed },
  building: { label: "Being built", tone: "neutral", Icon: Hammer },
  usable: { label: "Good coverage", tone: "accent", Icon: CircleDot },
  well_covered: { label: "Well covered", tone: "verified", Icon: ShieldCheck },
  // Enough content for a real guide; only its prices and hours are dated, and each of those already says how old it
  // is where it appears. A guide-level warning would sit on nearly every guide, so it is not shown as one.
  needs_refresh: { label: "Good coverage", tone: "accent", Icon: CircleDot },
};

/** One honest sentence on how much to lean on a guide at each maturity level. */
export const MATURITY_NOTE: Record<CoverageStatus, string> = {
  indexed: "We know this place exists but have no guide for it yet.",
  building: "This guide is still being built. Some sections are thin, so treat it as a starting point.",
  usable: "Good coverage from open travel sources. Details have not yet been confirmed by local contributors.",
  well_covered: "Well covered, and confirmed by contributors who know the place.",
  needs_refresh:
    "Good coverage from open travel sources. Prices and opening hours change, so each one shows when it was last recorded.",
};

export function coverageLabel(status: CoverageStatus | null | undefined): string {
  return META[status ?? "indexed"].label;
}

export function CoverageBadge({ status }: { status: CoverageStatus | null | undefined }) {
  const m = META[status ?? "indexed"];
  return (
    <Badge tone={m.tone}>
      <m.Icon aria-hidden />
      {m.label}
    </Badge>
  );
}
