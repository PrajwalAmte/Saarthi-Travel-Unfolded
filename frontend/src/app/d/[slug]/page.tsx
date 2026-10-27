import { ArrowRight } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";
import { CoverageBadge } from "@/components/coverage-badge";
import { ClimateChart } from "@/components/guide/climate-chart";
import { FactMeta } from "@/components/guide/fact-meta";
import { InShort } from "@/components/guide/in-short";
import { MappedPlaces } from "@/components/guide/mapped-places";
import { StatusPill } from "@/components/guide/fact-status";
import { PreferenceBar } from "@/components/guide/preference-bar";
import { Badge } from "@/components/ui/badge";
import { getDestination } from "@/lib/destination";
import { parsePreferences, preferencesToParams } from "@/lib/preferences";
import { apiGet } from "@/lib/server-api";
import type { Entry, FactSectionView, SectionView } from "@/lib/types";

export const dynamic = "force-dynamic";

type Props = {
  params: Promise<{ slug: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

async function section(slug: string, tab: string, prefs: string): Promise<SectionView | null> {
  try {
    return await apiGet<SectionView>(`/api/destinations/${slug}/sections/${tab}?size=3${prefs ? `&${prefs}` : ""}`);
  } catch {
    return null;
  }
}

function Highlight({ title, tab, slug, data, prefs }: { title: string; tab: string; slug: string; data: SectionView | null; prefs: string }) {
  const href = `/d/${slug}/${tab}${prefs ? `?${prefs}` : ""}`;
  return (
    <section className="flex flex-col rounded-lg border border-line bg-surface p-5">
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-xl font-semibold">{title}</h2>
        {data && data.totalPublished > 0 ? <span className="text-xs text-muted">{data.totalPublished} entries</span> : null}
      </div>
      {!data ? (
        <p className="mt-3 text-sm text-muted">Could not load this section.</p>
      ) : data.entries.items.length === 0 ? (
        <p className="mt-3 text-sm text-muted">Nothing published yet.</p>
      ) : (
        <ol className="mt-3 flex-1 space-y-4">
          {data.entries.items.map((e: Entry) => (
            <li key={e.id}>
              <p className="font-medium text-ink">{e.title}</p>
              <div className="mt-1 flex flex-wrap gap-1">
                {e.labels.slice(0, 3).map((l) => (
                  <Badge key={l.code} title={l.basis ?? undefined}>
                    {l.text}
                  </Badge>
                ))}
                {e.description && e.description.verificationStatus === "community_verified" ? (
                  <StatusPill status={e.description.verificationStatus} />
                ) : null}
              </div>
              {e.description ? <p className="mt-1 line-clamp-2 text-sm text-ink-2">{e.description.text}</p> : null}
            </li>
          ))}
        </ol>
      )}
      <Link href={href} className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-accent underline">
        See all <ArrowRight className="size-4" aria-hidden />
      </Link>
    </section>
  );
}

export default async function OverviewPage({ params, searchParams }: Props) {
  const { slug } = await params;
  const d = await getDestination(slug);
  if (!d) notFound();
  if (!d.guideAvailable) {
    return <MappedPlaces slug={slug} group={null} page={0} basePath={`/d/${slug}/map`} compact />;
  }
  const prefs = preferencesToParams(parsePreferences(await searchParams)).toString();
  const [eat, explore, stay, tips] = await Promise.all([
    section(slug, "eat", prefs),
    section(slug, "explore", prefs),
    section(slug, "stay", prefs),
    apiGet<FactSectionView>(`/api/destinations/${slug}/tips`).catch(() => null),
  ]);

  return (
    <div className="space-y-10">
      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <section>
          <h2 className="text-2xl font-semibold">About {d.name}</h2>
          {d.summary ? (
            <>
              <p className="mt-3 max-w-prose text-lg leading-relaxed text-ink-2">{d.summary.text}</p>
              <FactMeta fact={d.summary} destinationId={d.id} compact />
            </>
          ) : (
            <p className="mt-3 text-muted">No summary has been published yet.</p>
          )}
          {d.inShort?.length ? (
            <div className="mt-6">
              <InShort items={d.inShort} />
            </div>
          ) : null}
        </section>
        {tips?.climate ? (
          <aside>
            <h2 className="text-lg font-semibold">Climate at a glance</h2>
            <ClimateChart climate={tips.climate} />
            <Link href={`/d/${slug}/tips`} className="mt-2 inline-block text-sm text-accent underline">
              Seasons and local tips
            </Link>
          </aside>
        ) : null}
      </div>

      <PreferenceBar />

      <div className="grid gap-4 md:grid-cols-3">
        <Highlight title="Where to eat" tab="eat" slug={slug} data={eat} prefs={prefs} />
        <Highlight title="What to explore" tab="explore" slug={slug} data={explore} prefs={prefs} />
        <Highlight title="Where to stay" tab="stay" slug={slug} data={stay} prefs={prefs} />
      </div>

      {eat && eat.specialities.length ? (
        <section>
          <h2 className="text-2xl font-semibold">Local specialities</h2>
          <ul className="mt-3 flex flex-wrap gap-2">
            {eat.specialities.map((f) => (
              <li key={f.id}>
                <Link href={`/facts/${f.id}`} className="inline-flex rounded-md border border-line bg-surface px-3 py-1.5 text-sm hover:border-accent">
                  {f.heading}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

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

      {d.recentlyClosed.length ? (
        <section>
          <h2 className="text-lg font-semibold">Reported closed</h2>
          <ul className="mt-2 space-y-1 text-sm text-ink-2">
            {d.recentlyClosed.map((c) => (
              <li key={c.id}>
                <span className="font-medium">{c.title}</span> <span className="text-muted">— {c.reason}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
