import { Clock, MapPin, Tag } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";
import { StillTrue } from "@/components/guide/still-true";
import { Notice } from "@/components/ui/notice";
import { getDestination } from "@/lib/destination";
import { apiGetOrNull } from "@/lib/server-api";
import type { RecheckItem, RecheckView } from "@/lib/types";
import { formatMonth } from "@/lib/utils";

export const dynamic = "force-dynamic";
export const metadata = { title: "Help check this guide" };

const SECTIONS: { key: RecheckItem["section"]; title: string }[] = [
  { key: "eat", title: "Places to eat" },
  { key: "stay", title: "Places to stay" },
  { key: "explore", title: "Things to see and do" },
];

function age(item: RecheckItem): string {
  const m = formatMonth(item.fact.observedAt);
  return m ? `last recorded ${m}` : "no date recorded";
}

export default async function RecheckPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const d = await getDestination(slug);
  if (!d) notFound();
  const w = await apiGetOrNull<RecheckView>(`/api/destinations/${slug}/recheck`);
  if (!w) notFound();

  return (
    <div className="max-w-3xl space-y-8">
      <header>
        <h2 className="text-2xl font-semibold">Help check {d.name}</h2>
        <p className="mt-2 text-ink-2">
          These prices and opening hours come from travel-guide text that may be years old. If you know {d.name}, tell us which
          are still right. Each answer is recorded with your account and shown on the listing, so the next traveller can see it
          was checked.
        </p>
        <p className="mt-2 text-sm text-muted">
          {w.totalDue.toLocaleString("en-IN")} prices and hours here need a check. Below are the {w.shown} on the listings
          travellers see first.
        </p>
      </header>

      {w.items.length === 0 ? (
        <Notice tone="success" title="Nothing is waiting to be checked">
          Every price and opening time on the top listings was checked recently. Thank you to everyone who helped.
        </Notice>
      ) : (
        SECTIONS.map(({ key, title }) => {
          const items = w.items.filter((i) => i.section === key);
          if (!items.length) return null;
          return (
            <section key={key} aria-labelledby={`recheck-${key}`}>
              <div className="flex items-baseline justify-between gap-4">
                <h3 id={`recheck-${key}`} className="font-serif text-xl font-semibold">
                  {title}
                </h3>
                <Link href={`/d/${slug}/${key}`} className="text-sm text-accent underline">
                  Open {title.toLowerCase()}
                </Link>
              </div>
              <ol className="mt-3 divide-y divide-line rounded-lg border border-line bg-surface">
                {items.map((i) => (
                  <li key={i.fact.id} className="grid gap-x-4 gap-y-1 px-4 py-3 sm:grid-cols-[1fr_auto] sm:items-center">
                    <div className="min-w-0">
                      <p className="font-medium text-ink">
                        <span className="mr-2 text-sm font-normal text-muted">{i.rank}.</span>
                        {i.title}
                        {i.area ? <span className="font-normal text-muted"> · {i.area}</span> : null}
                      </p>
                      <p className="mt-0.5 flex flex-wrap items-center gap-x-2 text-sm text-ink-2">
                        {i.fact.kind === "price" ? <Tag className="size-3.5 text-muted" aria-hidden /> : <Clock className="size-3.5 text-muted" aria-hidden />}
                        <span className="sr-only">{i.fact.kind === "price" ? "Price: " : "Hours: "}</span>
                        <span className="line-clamp-2">{i.fact.text}</span>
                        <span className="text-xs text-muted">({age(i)})</span>
                      </p>
                    </div>
                    <div className="flex items-center gap-4 text-sm">
                      <a href={i.mapUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-accent underline">
                        <MapPin className="size-3.5" aria-hidden /> Map
                      </a>
                      <StillTrue fact={i.fact} destinationId={d.id} />
                    </div>
                  </li>
                ))}
              </ol>
            </section>
          );
        })
      )}

      <p className="text-sm text-muted">
        Not signed in? You will be asked to sign in with your email first. It takes a minute and there is no password.
      </p>
    </div>
  );
}
