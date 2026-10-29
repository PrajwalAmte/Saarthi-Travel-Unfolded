import { ArrowRight } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { JsonLd } from "@/components/json-ld";
import { apiGetOrNull } from "@/lib/server-api";
import { siteUrl } from "@/lib/site-url";
import { breadcrumbJsonLd, NOINDEX } from "@/lib/seo";
import { MIN_TRIPS, monthRanges } from "@/lib/trips";
import type { TripSource, WeekendTrips } from "@/lib/types";
import { formatDate, safeHref } from "@/lib/utils";

export const dynamic = "force-dynamic";

async function load(slug: string): Promise<WeekendTrips | null> {
  if (!/^[a-z0-9][a-z0-9-]{0,120}$/.test(slug)) return null;
  return apiGetOrNull<WeekendTrips>(`/api/trips/weekend-from/${slug}`);
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  const w = await load(slug);
  if (!w || w.total < MIN_TRIPS) return { title: "Not found", robots: NOINDEX };
  const title = `Weekend trips from ${w.originName}: ${w.total} places within ${w.maxKm} km`;
  const description = `${w.total} places within ${w.maxKm} km of ${w.originName} with travel guides, by distance: what each is known for, how to get there and the months its weather is mildest, all from cited sources.`;
  return {
    title,
    description,
    alternates: { canonical: `/trips/weekend-from/${w.originSlug}` },
    openGraph: { type: "article", title, description, url: `/trips/weekend-from/${w.originSlug}` },
    twitter: { card: "summary", title, description },
  };
}

function SourceLink({ s }: { s: TripSource }) {
  if (!s) return null;
  const href = s.url && safeHref(s.url);
  return href ? (
    <a href={href} target="_blank" rel="noopener noreferrer" className="text-xs text-muted underline">
      {s.name}
    </a>
  ) : (
    <span className="text-xs text-muted">{s.name}</span>
  );
}

export default async function WeekendTripsPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const w = await load(slug);
  if (!w || w.total < MIN_TRIPS) notFound();
  const base = siteUrl();
  const all = w.bands.flatMap((b) => b.trips);

  return (
    <div className="container-page max-w-5xl py-10">
      <JsonLd
        data={breadcrumbJsonLd(base, [
          { name: "Home", path: "/" },
          { name: w.originName, path: `/d/${w.originSlug}` },
          { name: `Weekend trips from ${w.originName}`, path: `/trips/weekend-from/${w.originSlug}` },
        ])}
      />
      <JsonLd
        data={{
          "@context": "https://schema.org",
          "@type": "ItemList",
          name: `Weekend trips from ${w.originName}`,
          numberOfItems: all.length,
          itemListElement: all.map((t, i) => ({
            "@type": "ListItem",
            position: i + 1,
            name: t.name,
            url: new URL(`/d/${t.slug}`, base).toString(),
          })),
        }}
      />
      <p className="text-sm text-muted">
        <Link href={`/d/${w.originSlug}`} className="hover:underline">
          {w.originName}
        </Link>
      </p>
      <h1 className="mt-1 text-4xl font-semibold leading-tight sm:text-5xl">Weekend trips from {w.originName}</h1>
      <p className="mt-3 max-w-3xl text-lg text-ink-2">
        {w.total} places within {w.maxKm} km that have a guide on Saarthi, nearest first. Each note is quoted from the place&apos;s
        guide with its source; weather months come from ten years of averages. Updated {formatDate(w.generatedAt)}.
      </p>

      {w.bands.map((band) => (
        <section key={band.label} className="mt-10" aria-labelledby={`band-${band.toKm}`}>
          <h2 id={`band-${band.toKm}`} className="text-2xl font-semibold">
            {band.label} <span className="text-base font-normal text-muted">({band.trips.length})</span>
          </h2>
          <ul className="mt-4 grid gap-5 md:grid-cols-2">
            {band.trips.map((t) => (
              <li key={t.slug} className="overflow-hidden rounded-lg border border-line bg-surface">
                {t.imageUrl && safeHref(t.imageUrl) ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={t.imageUrl} alt="" loading="lazy" className="aspect-[16/7] w-full object-cover" />
                ) : null}
                <div className="space-y-2 p-4">
                  <h3 className="font-serif text-xl font-semibold">
                    <Link href={`/d/${t.slug}`} className="hover:underline">
                      {t.name}
                    </Link>
                  </h3>
                  <p className="text-sm text-muted">
                    {[t.state, `about ${t.distanceKm} km in a straight line`].filter(Boolean).join(" · ")}
                  </p>
                  {t.knownFor ? (
                    <p className="text-sm text-ink-2">
                      {t.knownFor} <SourceLink s={t.knownForSource} />
                    </p>
                  ) : null}
                  {t.gettingThere ? (
                    <p className="text-sm text-ink-2">
                      <span className="font-medium text-ink">Getting there: </span>
                      {t.gettingThere} <SourceLink s={t.gettingThereSource} />
                    </p>
                  ) : null}
                  <p className="text-sm text-ink-2">
                    {t.comfortableMonths.length ? (
                      <>
                        <span className="font-medium text-ink">Mildest months: </span>
                        {monthRanges(t.comfortableMonths)}
                      </>
                    ) : t.climatePeriod ? (
                      <span className="text-muted">No month is both mild and mostly dry on average.</span>
                    ) : null}
                  </p>
                  <p className="flex flex-wrap items-center justify-between gap-2 text-sm">
                    <span className="text-muted">
                      {[
                        t.placesToEat ? `${t.placesToEat} to eat` : null,
                        t.placesToStay ? `${t.placesToStay} to stay` : null,
                        t.thingsToDo ? `${t.thingsToDo} to see and do` : null,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                    <Link href={`/d/${t.slug}`} className="inline-flex items-center gap-1 text-accent underline">
                      Open the guide <ArrowRight className="size-4" aria-hidden />
                    </Link>
                  </p>
                  {t.imageCredit ? <p className="text-[11px] text-muted">Photo: {t.imageCredit}</p> : null}
                </div>
              </li>
            ))}
          </ul>
        </section>
      ))}

      <section className="mt-12 border-t border-line pt-6" aria-labelledby="method">
        <h2 id="method" className="text-lg font-semibold">
          How this page is made
        </h2>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-ink-2">
          {w.method.map((m) => (
            <li key={m}>{m}</li>
          ))}
        </ul>
      </section>
    </div>
  );
}
