"use client";

import { CalendarDays } from "lucide-react";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { cn } from "@/lib/utils";

export const TABS = [
  { key: "", label: "Overview", count: null },
  { key: "eat", label: "Eat", count: "eat" },
  { key: "stay", label: "Stay", count: "stay" },
  { key: "explore", label: "Explore", count: "explore" },
  { key: "neighbourhoods", label: "Areas", count: "neighbourhoods" },
  { key: "budget", label: "Budget", count: "budget" },
  { key: "getting-around", label: "Getting around", count: "getting-around" },
  { key: "tips", label: "Local tips", count: "tips" },
  { key: "map", label: "Mapped places", count: null },
] as const;

const KEEP = ["food", "budget", "comfort", "interests"];

export function GuideTabs({ slug, counts, weekendTrips = null }: { slug: string; counts: Record<string, number>; weekendTrips?: number | null }) {
  const pathname = usePathname();
  const params = useSearchParams();
  const keep = new URLSearchParams();
  for (const k of KEEP) {
    const v = params.get(k);
    if (v) keep.set(k, v);
  }
  const qs = keep.toString();
  return (
    <nav aria-label="Guide sections" className="-mx-4 overflow-x-auto px-4 sm:mx-0 sm:px-0">
      <ul className="flex min-w-max gap-1 border-b border-line">
        {TABS.map((t) => {
          const href = `/d/${slug}${t.key ? `/${t.key}` : ""}`;
          const active = pathname === href;
          const count = t.count ? counts[t.count] : null;
          return (
            <li key={t.key}>
              <Link
                href={qs ? `${href}?${qs}` : href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "-mb-px inline-flex h-11 items-center gap-1.5 border-b-2 px-3 text-sm transition-colors",
                  active ? "border-accent font-medium text-ink" : "border-transparent text-muted hover:text-ink",
                )}
              >
                {t.label}
                {count !== null && count !== undefined ? (
                  <span className={cn("rounded-sm px-1 text-xs", count === 0 ? "text-muted/70" : "bg-sunken text-muted")}>{count}</span>
                ) : null}
              </Link>
            </li>
          );
        })}
        {weekendTrips ? (
          <li>
            <Link
              href={`/trips/weekend-from/${slug}`}
              className="-mb-px inline-flex h-11 items-center gap-1.5 border-b-2 border-transparent px-3 text-sm font-medium text-accent hover:underline"
            >
              <CalendarDays className="size-4" aria-hidden />
              Weekend trips
              <span className="rounded-sm bg-accent-soft px-1 text-xs">{weekendTrips}</span>
            </Link>
          </li>
        ) : null}
      </ul>
    </nav>
  );
}
