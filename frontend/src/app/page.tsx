import { ArrowRight, BadgeCheck, FileText, HeartHandshake } from "lucide-react";
import Link from "next/link";
import { HomeSearch } from "@/components/home-search";
import { Notice } from "@/components/ui/notice";
import { apiGet } from "@/lib/server-api";
import type { Meta, Page } from "@/lib/types";
import { safeHref } from "@/lib/utils";

export const dynamic = "force-dynamic";

type Guide = {
  slug: string;
  name: string;
  kind: string;
  description: string | null;
  stateName: string | null;
  imageUrl: string | null;
  imageCredit: string | null;
  entryCount: number | null;
};

async function load<T>(path: string): Promise<T | null> {
  try {
    return await apiGet<T>(path, { timeoutMs: 5000 });
  } catch {
    return null;
  }
}

export default async function HomePage() {
  const [meta, guides] = await Promise.all([
    load<Meta>("/api/meta"),
    load<Page<Guide>>("/api/destinations?withGuide=true&size=6"),
  ]);
  const indexed = meta?.indexedDestinations ?? 0;

  return (
    <>
      <section className="border-b border-line">
        <div className="container-page py-10 sm:py-16">
          <h1 className="max-w-3xl text-balance text-4xl font-semibold leading-[1.1] sm:text-5xl">
            Plan your trip anywhere in India.
          </h1>
          <p className="mt-4 max-w-2xl text-lg text-ink-2">
            Where to eat, where to stay, what to see, what things cost and how to get around — from open travel guides and
            people who know each place.
          </p>
          <div className="mt-8 max-w-3xl">
            <HomeSearch />
          </div>
          {meta === null ? (
            <Notice tone="warning" className="mt-6 max-w-3xl" title="Search is unavailable right now">
              Please try again in a moment.
            </Notice>
          ) : indexed > 0 ? (
            <p className="mt-6 text-sm text-muted">
              {indexed >= 1000 ? `${(Math.floor(indexed / 100) * 100).toLocaleString("en-IN")}+` : indexed.toLocaleString("en-IN")}{" "}
              destinations, from big cities to small towns.{" "}
              <Link href="/request" className="text-accent underline">
                Missing one? Ask for it.
              </Link>
            </p>
          ) : null}
        </div>
      </section>

      {guides && guides.items.length ? (
        <section className="container-page py-12 sm:py-14">
          <div className="flex items-baseline justify-between gap-4">
            <h2 className="text-2xl font-semibold sm:text-3xl">Guides to explore</h2>
            <Link href="/search" className="inline-flex items-center gap-1 text-sm text-accent underline">
              All destinations <ArrowRight className="size-4" aria-hidden />
            </Link>
          </div>
          <ul className="mt-6 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {guides.items.map((g) => (
              <li key={g.slug}>
                <Link
                  href={`/d/${g.slug}`}
                  className="group block overflow-hidden rounded-lg border border-line bg-surface transition-colors hover:border-line-strong"
                >
                  {g.imageUrl && safeHref(g.imageUrl) ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={g.imageUrl} alt="" loading="lazy" className="aspect-[16/9] w-full object-cover" />
                  ) : (
                    <div className="aspect-[16/9] w-full bg-sunken" aria-hidden />
                  )}
                  <div className="p-4">
                    <h3 className="font-serif text-xl font-semibold group-hover:underline">{g.name}</h3>
                    <p className="mt-0.5 text-sm text-muted">
                      {[g.stateName, g.entryCount ? `${g.entryCount} places to eat, stay and see` : null].filter(Boolean).join(" · ")}
                    </p>
                    {g.imageCredit ? <p className="mt-2 text-[11px] text-muted">Photo: {g.imageCredit}</p> : null}
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <section className="border-t border-line">
        <div className="container-page grid gap-8 py-12 sm:grid-cols-3">
          {[
            {
              Icon: FileText,
              title: "Every tip shows its source",
              text: "Follow any fact back to the guide or person it came from.",
            },
            {
              Icon: BadgeCheck,
              title: "Kept current by locals",
              text: "Travellers and residents confirm or correct details in one tap.",
            },
            {
              Icon: HeartHandshake,
              title: "Open and independent",
              text: "Built on open data. No paid placements, no sponsored rankings.",
            },
          ].map(({ Icon, title, text }) => (
            <div key={title}>
              <Icon className="size-5 text-accent" aria-hidden />
              <h3 className="mt-3 font-serif text-lg font-semibold">{title}</h3>
              <p className="mt-1 text-sm text-ink-2">{text}</p>
            </div>
          ))}
        </div>
        <div className="container-page flex flex-wrap gap-x-6 gap-y-2 pb-12 text-sm">
          <Link href="/about" className="text-accent underline">
            How guides are made
          </Link>
          <Link href="/signin" className="text-accent underline">
            Know a place well? Share what you know
          </Link>
        </div>
      </section>
    </>
  );
}
