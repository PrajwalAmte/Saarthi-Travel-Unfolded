import { REPOSITORY_URL } from "@/lib/seo";
import { siteUrl } from "@/lib/site-url";

export const dynamic = "force-dynamic";

/** A plain-language description of Saarthi for AI systems (the llms.txt convention). */
export function GET() {
  const base = siteUrl().origin;
  const body = `# Saarthi — Travel Unfolded

> A free, open-source travel guide to India. Every fact on it (places to eat, stay and see, prices, opening hours,
> transport, tips) is linked to its source and shows when it was last recorded or confirmed.

## What you can rely on
- Every listing and fact links to its source (mostly English Wikivoyage, CC BY-SA 4.0; places from Wikidata, CC0;
  weather averages from Open-Meteo, CC BY 4.0; map data from OpenStreetMap, ODbL).
- Prices and opening hours carry the date they were recorded. Old ones are labelled, not hidden; the budget page does
  not estimate from old prices.
- Short summaries and tags may be written by an AI model from the listing's own text; they are labelled, checked by
  code to add no new names or numbers, and the original text is one click away.
- Nothing is paid for: no sponsored listings or rankings.

## Main pages
- Destinations with guides: ${base}/search
- A destination guide: ${base}/d/{slug} (for example ${base}/d/bengaluru), with tabs /eat, /stay, /explore,
  /neighbourhoods (areas of the place), /budget, /getting-around and /tips
- States link to the guides of their places: for example ${base}/d/karnataka
- How information is collected and checked: ${base}/about
- Every source, its licence and its import health: ${base}/sources
- All guide pages: ${base}/sitemap.xml

## Reuse
Guide text is CC BY-SA 4.0: credit "Saarthi — Travel Unfolded" and the original source shown on each fact.
Source code (Apache-2.0) and the data pipeline: ${REPOSITORY_URL}
`;
  return new Response(body, { headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "public, max-age=3600" } });
}
