import Link from "next/link";
import { AccountMenu } from "./account-menu";
import { Brand } from "./brand";
import { MobileNav } from "./mobile-nav";
import { ThemeToggle } from "./theme-toggle";

export const NAV = [
  { href: "/search", label: "Destinations" },
  { href: "/about", label: "How it works" },
  { href: "/request", label: "Request a place" },
];

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-30 border-b border-line bg-paper/90 backdrop-blur supports-[backdrop-filter]:bg-paper/80">
      <div className="container-page flex h-16 items-center justify-between gap-4">
        <Brand />
        <nav aria-label="Main" className="hidden items-center gap-1 md:flex">
          {NAV.map((n) => (
            <Link
              key={n.href}
              href={n.href}
              className="rounded-full px-4 py-2 text-[0.95rem] font-medium text-ink-2 transition-colors hover:bg-sunken hover:text-ink"
            >
              {n.label}
            </Link>
          ))}
        </nav>
        <div className="flex items-center gap-1">
          <ThemeToggle />
          <AccountMenu />
          <MobileNav items={[...NAV, { href: "/sources", label: "Sources" }]} />
        </div>
      </div>
    </header>
  );
}
