import { describe, expect, it, vi } from "vitest";
import robots from "@/app/robots";
import {
  breadcrumbJsonLd,
  destinationCrumbs,
  destinationDescription,
  destinationTitle,
  firstSentences,
  jsonLdString,
  placeJsonLd,
  tabDescription,
  tabTitle,
} from "@/lib/seo";
import type { DestinationDetail } from "@/lib/types";

const apiGet = vi.fn();
vi.mock("@/lib/server-api", () => ({ apiGet: (...a: unknown[]) => apiGet(...a) }));

const base = new URL("https://saarthi.example");

function bengaluru(overrides: Partial<DestinationDetail> = {}): DestinationDetail {
  return {
    id: "1",
    slug: "bengaluru",
    name: "Bengaluru",
    kind: "city",
    description: "capital of Karnataka",
    latitude: 12.97,
    longitude: 77.59,
    wikidataUrl: "https://www.wikidata.org/wiki/Q1355",
    wikivoyageUrl: "https://en.wikivoyage.org/wiki/Bengaluru",
    parents: [{ relation: "in_state", slug: "karnataka", name: "Karnataka", kind: "state" }],
    summary: { text: "Bengaluru, formerly Bangalore, is the capital of Karnataka. It is known for its parks and pubs. And more." },
    sectionCounts: { eat: 143, stay: 69, explore: 178 },
    image: { url: "https://upload.wikimedia.org/x.jpg", width: 800, height: 600 },
    guideAvailable: true,
    ...overrides,
  } as unknown as DestinationDetail;
}

describe("page titles and descriptions", () => {
  it("say what each tab answers and how much is behind it", () => {
    const d = bengaluru();
    expect(tabTitle("eat", d)).toBe("Where to eat in Bengaluru: 143 places");
    expect(tabTitle("explore", d)).toBe("Things to do in Bengaluru: 178 sights and activities");
    expect(tabTitle("budget", d)).toBe("What things cost in Bengaluru: food, stays and entry fees");
    expect(tabDescription("eat", d)).toContain("143 places to eat and drink in Bengaluru, Karnataka");
    expect(tabDescription("eat", d)).not.toBe(tabDescription("stay", d));
  });

  it("describe a destination with the first sentences of its summary, never mid-word", () => {
    expect(destinationTitle(bengaluru())).toBe("Bengaluru travel guide: where to eat, stay and what to see");
    expect(destinationTitle(bengaluru({ kind: "state", name: "Goa", guidedPlaces: 15 }))).toBe("Goa travel guide: 15 places with guides");
    expect(
      destinationDescription(
        bengaluru({
          kind: "state",
          name: "Goa",
          guidedPlaces: 15,
          childDestinations: [
            { slug: "panaji", name: "Panaji", kind: "city", coverageStatus: "usable" },
            { slug: "x", name: "Nowhere", kind: "town", coverageStatus: "indexed" },
            { slug: "anjuna", name: "Anjuna", kind: "town", coverageStatus: "usable" },
          ],
        }),
      ),
    ).toBe("Travel guides to 15 places in Goa, including Panaji, Anjuna: where to eat, stay and what to see, with sources and dates.");
    expect(destinationDescription(bengaluru())).toBe("Bengaluru, formerly Bangalore, is the capital of Karnataka. It is known for its parks and pubs. And more.");
    expect(firstSentences("A".repeat(50) + ". " + "B".repeat(200), 155)).toBe("A".repeat(50) + ".");
    expect(firstSentences("word ".repeat(60), 40)?.endsWith("…")).toBe(true);
  });
});

describe("structured data", () => {
  it("describes the place with its location, state and open records", () => {
    const ld = placeJsonLd(base, bengaluru()) as Record<string, unknown>;
    expect(ld["@type"]).toBe("TouristDestination");
    expect(ld.url).toBe("https://saarthi.example/d/bengaluru");
    expect(ld.geo).toEqual({ "@type": "GeoCoordinates", latitude: 12.97, longitude: 77.59 });
    expect(ld.containedInPlace).toEqual({ "@type": "AdministrativeArea", name: "Karnataka" });
    expect(ld.sameAs).toEqual(["https://www.wikidata.org/wiki/Q1355", "https://en.wikivoyage.org/wiki/Bengaluru"]);
    expect((placeJsonLd(base, bengaluru({ kind: "state" })) as Record<string, unknown>)["@type"]).toBe("AdministrativeArea");
  });

  it("builds breadcrumbs from home through the state", () => {
    const ld = breadcrumbJsonLd(base, [...destinationCrumbs(bengaluru()), { name: "Eat", path: "/d/bengaluru/eat" }]);
    expect(ld.itemListElement.map((i) => i.name)).toEqual(["Home", "Karnataka", "Bengaluru", "Eat"]);
    expect(ld.itemListElement[3]!.item).toBe("https://saarthi.example/d/bengaluru/eat");
    expect(ld.itemListElement[3]!.position).toBe(4);
  });

  it("cannot be broken out of its script tag", () => {
    expect(jsonLdString({ name: "</script><script>alert(1)</script>" })).not.toContain("</script>");
  });
});

describe("robots.txt", () => {
  it("lets every crawler, AI crawlers included, read the guides and points at the sitemap", () => {
    const r = robots();
    const rule = Array.isArray(r.rules) ? r.rules[0]! : r.rules;
    expect(rule.userAgent).toBe("*");
    expect(rule.allow).toBe("/");
    expect(rule.disallow).toEqual(expect.arrayContaining(["/admin", "/account", "/requests/", "/api/"]));
    expect(rule.disallow).not.toContain("/d/");
    expect(String(r.sitemap)).toMatch(/\/sitemap\.xml$/);
  });
});

describe("sitemap.xml", () => {
  it("lists guides and the tabs that have content, and survives the guide service being asleep", async () => {
    const { default: sitemap } = await import("@/app/sitemap");
    apiGet.mockResolvedValueOnce([
      { slug: "bengaluru", kind: "city", lastModified: "2026-10-27T00:00:00Z", tabs: ["eat", "stay"] },
      { slug: "karnataka", kind: "state", lastModified: null, tabs: [] },
    ]);
    const urls = (await sitemap()).map((e) => e.url);
    expect(urls).toEqual(expect.arrayContaining([expect.stringMatching(/\/d\/bengaluru$/), expect.stringMatching(/\/d\/bengaluru\/eat$/),
      expect.stringMatching(/\/d\/karnataka$/)]));
    expect(urls.some((u) => u.endsWith("/d/bengaluru/tips"))).toBe(false);

    apiGet.mockRejectedValueOnce(new Error("asleep"));
    const fallback = (await sitemap()).map((e) => e.url);
    expect(fallback.some((u) => u.endsWith("/about"))).toBe(true);
  });
});

describe("llms.txt", () => {
  it("describes Saarthi, its sources and where to start", async () => {
    const { GET } = await import("@/app/llms.txt/route");
    const text = await GET().text();
    expect(text).toContain("# Saarthi — Travel Unfolded");
    expect(text).toContain("sitemap.xml");
    expect(text).toContain("github.com/PrajwalAmte/Saarthi-Travel-Unfolded");
    expect(text).toContain("labelled");
  });
});
