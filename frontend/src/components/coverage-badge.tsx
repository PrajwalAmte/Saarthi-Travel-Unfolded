import { CircleDashed, CircleDot, Hammer, ShieldCheck } from "lucide-react";
import type { CoverageStatus } from "@/lib/types";
import { Badge } from "./ui/badge";

// Labels say what a traveller gets, not the internal review state.
const META: Record<CoverageStatus, { label: string; tone: "neutral" | "accent" | "verified" | "stale" | "source"; Icon: typeof CircleDot }> = {
  indexed: { label: "No guide yet", tone: "neutral", Icon: CircleDashed },
  building: { label: "Guide in progress", tone: "neutral", Icon: Hammer },
  usable: { label: "Guide", tone: "accent", Icon: CircleDot },
  well_covered: { label: "Full guide", tone: "verified", Icon: ShieldCheck },
  needs_refresh: { label: "Guide", tone: "accent", Icon: CircleDot },
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
