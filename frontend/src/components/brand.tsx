import Link from "next/link";
import { cn } from "@/lib/utils";

/** The mark: a sun over mountains, with a winding road leading up into them (a saarthi is a guide on the way). */
export function BrandMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className={cn("size-9 shrink-0 text-accent", className)}>
      <rect width="32" height="32" rx="9" fill="currentColor" />
      <circle cx="24" cy="8.5" r="2.4" fill="var(--color-accent-ink)" opacity="0.85" />
      <path d="M3 26 L12 10.5 L17.3 19.2 L20.8 14.2 L29 26 Z" fill="var(--color-accent-ink)" />
      <path d="M11.2 26 C13.6 22.6 19.8 22.4 17.4 19.1 C16.4 17.7 17.6 16.6 16.9 15.2 L17.9 15.2 C18.7 16.7 17.6 18 18.7 19.4 C21.6 22.9 16.6 23.4 20.6 26 Z" fill="currentColor" />
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
