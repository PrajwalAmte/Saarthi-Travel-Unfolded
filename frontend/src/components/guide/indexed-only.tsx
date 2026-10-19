import { MapPin } from "lucide-react";
import Link from "next/link";
import type { DestinationDetail } from "@/lib/types";
import { googleMapsHref } from "@/lib/utils";
import { CoverageBadge } from "../coverage-badge";
import { Button } from "../ui/button";

/** Shown for places that are in the index but have no published guide content. */
export function IndexedOnly({ d }: { d: DestinationDetail }) {
  const mapHref = googleMapsHref(d.latitude, d.longitude, `${d.name}, India`);
  return (
    <div className="space-y-8 border-t border-line pt-6">
      <p className="max-w-2xl text-ink-2">
        There&apos;s no written guide for {d.name} yet. Below are places on the map nearby; if you know {d.name}, your tips
        can start its guide.
      </p>
      <div className="flex flex-wrap gap-3">
        <Button asChild>
          <Link href={`/request?q=${encodeURIComponent(d.name)}`}>Ask for a {d.name} guide</Link>
        </Button>
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
          <h2 className="text-2xl font-semibold">Places in {d.name}</h2>
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
