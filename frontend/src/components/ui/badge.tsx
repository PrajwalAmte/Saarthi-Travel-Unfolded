import * as React from "react";
import { cn } from "@/lib/utils";

type Tone = "neutral" | "accent" | "verified" | "source" | "community" | "stale" | "conflict";

const tones: Record<Tone, string> = {
  neutral: "bg-sunken text-ink-2 border-line",
  accent: "bg-accent-soft text-accent border-transparent",
  verified: "bg-verified-soft text-verified border-transparent",
  source: "bg-source-soft text-source border-transparent",
  community: "bg-community-soft text-community border-transparent",
  stale: "bg-stale-soft text-stale border-transparent",
  conflict: "bg-conflict-soft text-conflict border-transparent",
};

export function Badge({
  tone = "neutral",
  className,
  ...props
}: React.HTMLAttributes<HTMLSpanElement> & { tone?: Tone }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-sm border px-1.5 py-0.5 text-xs font-medium leading-tight [&_svg]:size-3.5",
        tones[tone],
        className,
      )}
      {...props}
    />
  );
}
