import Link from "next/link";
import { cn } from "@/lib/utils";

/** The mark: a winding road leading to a destination (a saarthi is a guide on the way). */
export function BrandMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className={cn("size-9 shrink-0 text-accent", className)}>
      <rect width="32" height="32" rx="9" fill="currentColor" />
      <path
        d="M9.5 25.5c0-4.5 13-3.6 13-8.6 0-4.3-8-3.2-8-7.4"
        fill="none"
        stroke="var(--color-accent-ink)"
        strokeWidth="2.6"
        strokeLinecap="round"
      />
      <circle cx="14.5" cy="7.2" r="2.6" fill="var(--color-accent-ink)" />
    </svg>
  );
}

/** Logo and name, linking home. */
export function Brand({ className }: { className?: string }) {
  return (
    <Link href="/" className={cn("group flex items-center gap-2.5", className)} aria-label="Saarthi, Travel Unfolded — home">
      <BrandMark />
      <span className="flex flex-col">
        <span className="font-serif text-[1.4rem] font-semibold leading-none tracking-tight text-ink">Saarthi</span>
        <span className="mt-1 text-[0.68rem] font-semibold uppercase leading-none tracking-[0.2em] text-muted group-hover:text-accent">
          Travel Unfolded
        </span>
      </span>
    </Link>
  );
}
