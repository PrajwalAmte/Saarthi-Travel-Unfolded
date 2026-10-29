import { MapPin } from "lucide-react";
import Link from "next/link";
import { type DestinationDetail, hubNoun, isHub } from "@/lib/types";
import { googleMapsHref } from "@/lib/utils";
import { CoverageBadge } from "../coverage-badge";
import { Button } from "../ui/button";

/** Shown for places without their own guide: a state's hub of guides, or a place that has no guide yet. */
export function IndexedOnly({ d }: { d: DestinationDetail }) {
  const mapHref = googleMapsHref(d.latitude, d.longitude, `${d.name}, India`);
  const hub = isHub(d);
  return (
    <div className="space-y-8 border-t border-line pt-6">
      {hub ? (
        <div className="max-w-2xl space-y-3">
          {d.summary ? <p className="text-lg leading-relaxed text-ink-2">{d.summary.text}</p> : null}
          <p className="text-ink-2">
            {d.name} is {hubNoun(d.kind)}, so its guide is made of guides for the places in it. Pick a town or city below.
          </p>
        </div>
      ) : (
        <p className="max-w-2xl text-ink-2">
          There&apos;s no written guide for {d.name} yet. Below are places on the map nearby; if you know {d.name}, your tips
          can start its guide.
        </p>
      )}
      <div className="flex flex-wrap gap-3">
        {hub ? null : (
          <Button asChild>
            <Link href={`/request?q=${encodeURIComponent(d.name)}`}>Ask for a {d.name} guide</Link>
          </Button>
        )}
        <Button asChild variant="outline">
          <Link href={`/contribute/${d.slug}`}>Add a tip</Link>
        </Button>
        {mapHref ? (
          <Button asChild variant="ghost">
            <a href={mapHref} target="_blank" rel="noopener noreferrer">
              <MapPin /> View on Google Maps
            </a>
          </Button>
        ) : null}
      </div>
      {d.childDestinations.length ? (
        <section>
          <h2 className="text-2xl font-semibold">{hub ? `Guides in ${d.name}` : `Places in ${d.name}`}</h2>
          <ul className="mt-4 grid gap-x-6 sm:grid-cols-2 lg:grid-cols-3">
            {d.childDestinations.map((c) => (
              <li key={c.slug} className="flex items-center justify-between gap-3 border-b border-line py-3">
                <Link href={`/d/${c.slug}`} className="font-medium hover:text-accent hover:underline">
                  {c.name}
                </Link>
                <CoverageBadge status={c.coverageStatus} />
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
