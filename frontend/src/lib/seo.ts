import { type DestinationDetail, isHub } from "./types";

/** Repository shown to people and AI systems that want to check how Saarthi works. */
export const REPOSITORY_URL = "https://github.com/PrajwalAmte/Saarthi-Travel-Unfolded";
export const SITE_NAME = "Saarthi — Travel Unfolded";

/** Pages that are personal, transactional or endless, kept out of search indexes (links on them are still followed). */
export const NOINDEX = { index: false, follow: true } as const;

/** The first sentence or two of a text, cut at a sentence end and never longer than {@code max} characters. */
export function firstSentences(text: string | null | undefined, max = 155): string | null {
  if (!text) return null;
  const clean = text.replace(/\s+/g, " ").trim();
  if (clean.length <= max) return clean;
  const cut = clean.slice(0, max);
  const end = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "));
  if (end > max / 4) return cut.slice(0, end + 1);
  return `${cut.slice(0, cut.lastIndexOf(" "))}…`;
}

export function stateOf(d: Pick<DestinationDetail, "parents">): string | null {
  return d.parents.find((p) => p.relation === "in_state")?.name ?? null;
}

const n = (v: number | undefined) => (v && v > 0 ? v.toLocaleString("en-IN") : null);

/** Page title for a guide tab: says what the page answers and how much evidence is behind it. */
export function tabTitle(tab: string, d: Pick<DestinationDetail, "name" | "sectionCounts">): string {
  const c = d.sectionCounts ?? {};
  switch (tab) {
    case "eat":
      return n(c.eat) ? `Where to eat in ${d.name}: ${n(c.eat)} places` : `Where to eat in ${d.name}`;
    case "stay":
      return n(c.stay) ? `Where to stay in ${d.name}: ${n(c.stay)} options` : `Where to stay in ${d.name}`;
    case "explore":
      return n(c.explore) ? `Things to do in ${d.name}: ${n(c.explore)} sights and activities` : `Things to do in ${d.name}`;
    case "neighbourhoods":
      return `Neighbourhoods of ${d.name}: which area to stay in`;
    case "budget":
      return `What things cost in ${d.name}: food, stays and entry fees`;
    case "getting-around":
      return `Getting to and around ${d.name}`;
    case "tips":
      return `${d.name} travel tips: safety, money and local customs`;
    case "map":
      return `Places on the map in ${d.name}`;
    default:
      return `${d.name} travel guide`;
  }
}

/** Meta description for a guide tab, specific to the page, stating where the information comes from. */
export function tabDescription(tab: string, d: Pick<DestinationDetail, "name" | "parents" | "sectionCounts">): string {
  const where = stateOf(d) ? `${d.name}, ${stateOf(d)}` : d.name;
  const c = d.sectionCounts ?? {};
  switch (tab) {
    case "eat":
      return `${n(c.eat) ? `${n(c.eat)} places` : "Places"} to eat and drink in ${where}: what to order, prices with the date they were recorded, and the source of every listing.`;
    case "stay":
      return `${n(c.stay) ? `${n(c.stay)} places` : "Places"} to stay in ${where}, from budget to premium, with prices dated and every listing linked to its source.`;
    case "explore":
      return `${n(c.explore) ? `${n(c.explore)} sights` : "Sights"}, activities and shops in ${where}, each with its source, opening details and a map link.`;
    case "neighbourhoods":
      return `The areas of ${where} and what each is like, to help you choose where to stay.`;
    case "budget":
      return `Typical prices for food, stays and entry fees in ${where}, computed from dated listings. Old prices are flagged, not hidden.`;
    case "getting-around":
      return `How to get to ${where} and how to move around once there: trains, buses, autos, metro and taxis, with sources.`;
    case "tips":
      return `Practical tips for ${where}: safety, money, health, connectivity and local customs, each linked to its source.`;
    default:
      return `Open, source-linked travel guide to ${where}.`;
  }
}

export function destinationTitle(d: Pick<DestinationDetail, "name" | "kind" | "guidedPlaces">): string {
  if (isHub(d)) {
    return `${d.name} travel guide: ${d.guidedPlaces} places with guides`;
  }
  return `${d.name} travel guide: where to eat, stay and what to see`;
}

export function destinationDescription(
  d: Pick<DestinationDetail, "name" | "summary" | "description" | "parents" | "kind"> &
    Partial<Pick<DestinationDetail, "guidedPlaces" | "childDestinations">>,
): string {
  if (isHub(d)) {
    const named = (d.childDestinations ?? [])
      .filter((c) => c.coverageStatus && c.coverageStatus !== "indexed")
      .slice(0, 3)
      .map((c) => c.name);
    return `Travel guides to ${d.guidedPlaces} places in ${d.name}${named.length ? `, including ${named.join(", ")}` : ""}: where to eat, stay and what to see, with sources and dates.`;
  }
  return (
    firstSentences(d.summary?.text) ??
    `${d.name}${stateOf(d) ? `, ${stateOf(d)}` : ""}: ${d.description ?? "destination in India"}. Open, source-linked travel guide.`
  );
}

type Crumb = { name: string; path: string };

/** schema.org BreadcrumbList: Home, state, destination and, on a tab, the tab. */
export function breadcrumbJsonLd(base: URL, crumbs: Crumb[]) {
  return {
    "@context": "https://schema.org",
    "@type": "BreadcrumbList",
    itemListElement: crumbs.map((c, i) => ({
      "@type": "ListItem",
      position: i + 1,
      name: c.name,
      item: new URL(c.path, base).toString(),
    })),
  };
}

export function destinationCrumbs(d: Pick<DestinationDetail, "name" | "slug" | "parents">): Crumb[] {
  const state = d.parents.find((p) => p.relation === "in_state");
  return [
    { name: "Home", path: "/" },
    ...(state && state.slug !== d.slug ? [{ name: state.name, path: `/d/${state.slug}` }] : []),
    { name: d.name, path: `/d/${d.slug}` },
  ];
}

/**
 * schema.org description of the place itself: what it is, where it is, and the open records it is the same as.
 * A state is described as an administrative area; anything else as a tourist destination.
 */
export function placeJsonLd(base: URL, d: DestinationDetail) {
  const url = new URL(`/d/${d.slug}`, base).toString();
  const state = stateOf(d);
  const sameAs = [d.wikidataUrl, d.wikivoyageUrl].filter(Boolean);
  const isRegion = d.kind === "state" || d.kind === "union_territory" || d.kind === "district";
  return {
    "@context": "https://schema.org",
    "@type": isRegion ? "AdministrativeArea" : "TouristDestination",
    name: d.name,
    url,
    description: destinationDescription(d),
    ...(d.latitude !== null && d.longitude !== null
      ? { geo: { "@type": "GeoCoordinates", latitude: d.latitude, longitude: d.longitude } }
      : {}),
    ...(state && !isRegion ? { containedInPlace: { "@type": "AdministrativeArea", name: state } } : {}),
    ...(sameAs.length ? { sameAs } : {}),
    ...(d.image?.url ? { image: d.image.url } : {}),
  };
}

/** Escapes a JSON-LD object for a script tag ("</script>" inside a string must not end the tag). */
export function jsonLdString(data: unknown): string {
  return JSON.stringify(data).replace(/</g, "\\u003c");
}
