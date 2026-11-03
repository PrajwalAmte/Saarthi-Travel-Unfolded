import { ArrowRight } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { DestinationHeader } from "@/components/guide/destination-header";
import { GuideTabs } from "@/components/guide/guide-tabs";
import { IndexedOnly } from "@/components/guide/indexed-only";
import { JsonLd } from "@/components/json-ld";
import { getDestination } from "@/lib/destination";
import { destinationDescription, destinationTitle, NOINDEX, placeJsonLd } from "@/lib/seo";
import { siteUrl } from "@/lib/site-url";
import { isHub } from "@/lib/types";
import { tripOrigins, weekendTripCount } from "@/lib/weekend-trips";

type Props = { children: React.ReactNode; params: Promise<{ slug: string }> };

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  const d = await getDestination(slug);
  if (!d) return { title: "Destination not found", robots: NOINDEX };
  const title = destinationTitle(d);
  const description = destinationDescription(d);
  const indexable = d.guideAvailable || isHub(d);
  return {
    title,
    description,
    alternates: { canonical: `/d/${d.slug}` },
    // A place with no guide yet is a thin page: keep it out of indexes until it has content, but follow its links.
    ...(indexable ? {} : { robots: NOINDEX }),
    openGraph: {
      type: "article",
      title,
      description,
      url: `/d/${d.slug}`,
      ...(d.image?.url
        ? { images: [{ url: d.image.url, width: d.image.width ?? undefined, height: d.image.height ?? undefined, alt: d.name }] }
        : {}),
    },
    twitter: { card: d.image?.url ? "summary_large_image" : "summary", title, description },
  };
}

export default async function DestinationLayout({ children, params }: Props) {
  const { slug } = await params;
  const d = await getDestination(slug);
  if (!d) notFound();
  const weekendTrips = d.guideAvailable ? await weekendTripCount(d.slug) : null;
  // A state's page points to weekend trips from the cities in it (Goa to Panaji).
  const hubTrips = isHub(d) ? (await tripOrigins()).filter((o) => o.stateSlug === d.slug) : [];
  return (
    <div className="container-page">
      {d.guideAvailable || isHub(d) ? <JsonLd data={placeJsonLd(siteUrl(), d)} /> : null}
      <DestinationHeader d={d} />
      {d.guideAvailable ? (
        <>
          <Suspense fallback={<div className="h-11 border-b border-line" />}>
            <GuideTabs slug={d.slug} counts={d.sectionCounts} weekendTrips={weekendTrips} />
          </Suspense>
          <div className="pt-6">{children}</div>
        </>
      ) : (
        <>
          {hubTrips.map((o) => (
            <Link
              key={o.slug}
              href={`/trips/weekend-from/${o.slug}`}
              className="mt-6 flex items-center justify-between gap-3 rounded-lg border border-accent/40 bg-accent-soft p-5 hover:border-accent"
            >
              <span>
                <span className="block text-xl font-semibold">Weekend trips from {o.name}</span>
                <span className="mt-1 block text-sm text-ink-2">{o.total} places with guides, by drive time.</span>
              </span>
              <ArrowRight className="size-5 shrink-0 text-accent" aria-hidden />
            </Link>
          ))}
          <IndexedOnly d={d} />
          <div className="pb-10 pt-8">{children}</div>
        </>
      )}
    </div>
  );
}
