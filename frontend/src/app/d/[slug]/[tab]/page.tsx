import Link from "next/link";
import { notFound } from "next/navigation";
import { BudgetTable } from "@/components/guide/budget-table";
import { ClimateChart } from "@/components/guide/climate-chart";
import { FactCard } from "@/components/guide/fact-card";
import { FactGroups } from "@/components/guide/fact-groups";
import { FactMeta } from "@/components/guide/fact-meta";
import { MappedPlaces } from "@/components/guide/mapped-places";
import { PreferenceBar } from "@/components/guide/preference-bar";
import { SectionList } from "@/components/guide/section-list";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { JsonLd } from "@/components/json-ld";
import { getDestination } from "@/lib/destination";
import { breadcrumbJsonLd, destinationCrumbs, NOINDEX, tabDescription, tabTitle } from "@/lib/seo";
import { siteUrl } from "@/lib/site-url";
import { parsePreferences, preferencesToParams } from "@/lib/preferences";
import { apiGet } from "@/lib/server-api";
import type { BudgetView, FactSectionView, NeighbourhoodSectionView, SectionView } from "@/lib/types";

export const dynamic = "force-dynamic";

const TABS = ["eat", "stay", "explore", "neighbourhoods", "budget", "getting-around", "tips", "map"] as const;
type Tab = (typeof TABS)[number];
const TITLES: Record<Tab, string> = {
  eat: "Eat",
  stay: "Stay",
  explore: "Explore",
  neighbourhoods: "Neighbourhoods",
  budget: "Budget",
  "getting-around": "Getting around",
  tips: "Local tips",
  map: "Mapped places",
};

type Props = {
  params: Promise<{ slug: string; tab: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export async function generateMetadata({ params, searchParams }: Props) {
  const { slug, tab } = await params;
  const d = await getDestination(slug);
  if (!d || !(TABS as readonly string[]).includes(tab)) return { title: "Not found", robots: NOINDEX };
  const sp = await searchParams;
  const page = Number.parseInt((Array.isArray(sp.page) ? sp.page[0] : sp.page) ?? "0", 10);
  const title = tabTitle(tab, d);
  const description = tabDescription(tab, d);
  // Preference and filter parameters reorder the same listings, so they all point at one canonical page.
  const canonical = `/d/${slug}/${tab}${Number.isFinite(page) && page > 0 ? `?page=${page}` : ""}`;
  return {
    title: Number.isFinite(page) && page > 0 ? `${title} (page ${page + 1})` : title,
    description,
    alternates: { canonical },
    // The map tab lists raw map records, not guide content.
    ...(tab === "map" || !d.guideAvailable ? { robots: NOINDEX } : {}),
    openGraph: {
      type: "article",
      title,
      description,
      url: canonical,
      ...(d.image?.url ? { images: [{ url: d.image.url, alt: d.name }] } : {}),
    },
    twitter: { card: d.image?.url ? "summary_large_image" : "summary", title, description },
  };
}

function TabCrumbs({ d, tab }: { d: NonNullable<Awaited<ReturnType<typeof getDestination>>>; tab: Tab }) {
  return (
    <JsonLd data={breadcrumbJsonLd(siteUrl(), [...destinationCrumbs(d), { name: TITLES[tab], path: `/d/${d.slug}/${tab}` }])} />
  );
}

export default async function TabPage(props: Props) {
  const { slug, tab } = await props.params;
  const d = (TABS as readonly string[]).includes(tab) ? await getDestination(slug) : null;
  return (
    <>
      {d ? <TabCrumbs d={d} tab={tab as Tab} /> : null}
      <TabContent {...props} />
    </>
  );
}

async function TabContent({ params, searchParams }: Props) {
  const { slug, tab } = await params;
  if (!(TABS as readonly string[]).includes(tab)) notFound();
  const d = await getDestination(slug);
  if (!d) notFound();
  const sp = await searchParams;
  if (tab === "map") {
    const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? null;
    const group = one(sp.group);
    const page = Number.parseInt(one(sp.page) ?? "0", 10);
    return (
      <MappedPlaces
        slug={slug}
        group={group && /^(eat|drink|stay|see|do|buy|transport)$/.test(group) ? group : null}
        page={Number.isFinite(page) && page > 0 ? Math.min(page, 300) : 0}
        basePath={`/d/${slug}/map`}
      />
    );
  }
  if (!d.guideAvailable) notFound();
  const current = new URLSearchParams();
  for (const [k, v] of Object.entries(sp)) {
    const value = Array.isArray(v) ? v[0] : v;
    if (value) current.set(k, value);
  }

  if (tab === "eat" || tab === "stay" || tab === "explore") {
    const api = preferencesToParams(parsePreferences(sp));
    const category = current.get("category");
    if (category && /^(eat|drink|stay|see|do|buy)$/.test(category)) api.set("category", category);
    const page = Number.parseInt(current.get("page") ?? "0", 10);
    if (Number.isFinite(page) && page > 0) api.set("page", String(page));
    api.set("size", "12");
    const data = await apiGet<SectionView>(`/api/destinations/${slug}/sections/${tab}?${api}`);
    return (
      <div className="space-y-6">
        <PreferenceBar />
        <SectionList data={data} slug={slug} destinationId={d.id} basePath={`/d/${slug}/${tab}`} params={current} />
      </div>
    );
  }

  if (tab === "neighbourhoods") {
    const data = await apiGet<NeighbourhoodSectionView>(`/api/destinations/${slug}/neighbourhoods`);
    const empty = !data.neighbourhoods.length && !data.stayNotes.length && !data.orientation.length && !data.listingAreas.length;
    return (
      <div className="space-y-10">
        {empty ? (
          <Notice tone="info" title="No neighbourhood information yet">
            No source has described this destination’s neighbourhoods yet.{" "}
            <Link href={`/contribute/${slug}`} className="underline">
              Add a neighbourhood note
            </Link>{" "}
            if you know the area.
          </Notice>
        ) : null}
        {data.neighbourhoods.length ? (
          <section>
            <h2 className="text-2xl font-semibold">Areas of {d.name}</h2>
            <div className="mt-4 grid gap-4 md:grid-cols-2">
              {data.neighbourhoods.map(({ entry, localities }) => (
                <article key={entry.id} className="rounded-lg border border-line bg-surface p-4">
                  <h3 className="font-serif text-xl font-semibold">{entry.title}</h3>
                  {entry.description ? (
                    <>
                      <p className="mt-1 text-ink-2">{entry.description.text}</p>
                      <FactMeta fact={entry.description} destinationId={d.id} compact />
                    </>
                  ) : null}
                  {localities.length ? (
                    <p className="mt-3 text-sm text-muted">
                      <span className="font-medium text-ink-2">Includes: </span>
                      {localities.join(", ")}
                    </p>
                  ) : null}
                </article>
              ))}
            </div>
          </section>
        ) : null}
        {data.stayNotes.length ? (
          <section>
            <h2 className="text-2xl font-semibold">Where to stay</h2>
            {data.stayNotes.map((f) => (
              <FactCard key={f.id} fact={f} destinationId={d.id} />
            ))}
          </section>
        ) : null}
        {data.orientation.length ? (
          <section>
            <h2 className="text-2xl font-semibold">Finding your way</h2>
            {data.orientation.map((f) => (
              <FactCard key={f.id} fact={f} destinationId={d.id} />
            ))}
          </section>
        ) : null}
        {data.listingAreas.length ? (
          <section>
            <h2 className="text-2xl font-semibold">Listings by area</h2>
            <ul className="mt-3 grid gap-2 sm:grid-cols-2">
              {data.listingAreas.map((a) => (
                <li key={a.area} className="flex items-center justify-between rounded-md border border-line px-3 py-2 text-sm">
                  <span className="font-medium">{a.area}</span>
                  <span className="flex flex-wrap gap-1">
                    {Object.entries(a.sections).map(([s, n]) => (
                      <Badge key={s}>
                        {n} {s}
                      </Badge>
                    ))}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        ) : null}
      </div>
    );
  }

  if (tab === "budget") {
    const data = await apiGet<BudgetView>(`/api/destinations/${slug}/budget`);
    return (
      <div className="max-w-4xl space-y-4">
        <h2 className="text-2xl font-semibold">What things cost in {d.name}</h2>
        <BudgetTable data={data} slug={slug} />
      </div>
    );
  }

  const data = await apiGet<FactSectionView>(`/api/destinations/${slug}/${tab}`);
  return (
    <div className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <div className="min-w-0">
        {data.groups.length ? (
          <FactGroups groups={data.groups} destinationId={d.id} />
        ) : (
          <Notice tone="info" title={`No ${TITLES[tab as Tab].toLowerCase()} information yet`}>
            Nothing reliable has been recorded here yet.{" "}
            <Link className="underline" href={`/contribute/${slug}`}>
              Share a tip
            </Link>{" "}
            if you know the area.
          </Notice>
        )}
      </div>
      {tab === "tips" && data.climate ? (
        <aside className="space-y-3">
          <h2 className="text-lg font-semibold">Climate</h2>
          <ClimateChart climate={data.climate} />
          <p className="text-sm text-ink-2">{data.climate.fact.text}</p>
          <FactMeta fact={data.climate.fact} destinationId={d.id} compact />
        </aside>
      ) : null}
    </div>
  );
}
