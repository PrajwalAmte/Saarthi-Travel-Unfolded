import type { Entry, Fact, SearchHit } from "@/lib/types";

/** Test-only fixtures; never imported by application code. */
export function fact(overrides: Partial<Fact> = {}): Fact {
  return {
    id: "00000000-0000-0000-0000-000000000001",
    entryId: null,
    kind: "description",
    section: "eat",
    heading: null,
    text: "A test description from a fixture.",
    value: null,
    volatility: "semi_stable",
    origin: "ingested",
    observedAt: "2021-03-26",
    observedBasis: "source_listing_date",
    sourceRefreshedAt: "2026-10-01T00:00:00Z",
    lastVerifiedAt: null,
    reviewDueAt: "2022-03-26",
    freshness: "due",
    verificationStatus: "needs_reverification",
    verificationCount: 0,
    denialCount: 0,
    pendingCorrections: 0,
    ownerSubmitted: false,
    evidence: [
      {
        sourceId: "wikivoyage-en",
        sourceName: "English Wikivoyage",
        sourceCategory: "open_guide",
        licenceName: "CC BY-SA 4.0",
        licenceUrl: "https://creativecommons.org/licenses/by-sa/4.0/",
        url: "https://en.wikivoyage.org/wiki/Testville",
        permalink: "https://en.wikivoyage.org/w/index.php?title=Testville&oldid=1",
        title: "Wikivoyage: Testville",
        excerpt: null,
        extractionMethod: "wikivoyage_listing_parser",
        sourceRevision: "1",
        sourceRevisionAt: "2026-10-01T00:00:00Z",
        observedAt: "2021-03-26",
        retrievedAt: "2026-10-01T00:00:00Z",
        contributorName: null,
      },
    ],
    ...overrides,
  };
}

export function entry(overrides: Partial<Entry> = {}): Entry {
  return {
    id: "00000000-0000-0000-0000-0000000000e1",
    section: "eat",
    subsection: "Budget",
    area: null,
    title: "Test Tiffin Room",
    altTitle: null,
    latitude: 12.3,
    longitude: 76.6,
    distanceKm: 1.2,
    origin: "ingested",
    labels: [{ code: "price_tier", text: "Budget-friendly", basis: "Listed under “Budget”" }],
    description: fact(),
    details: [
      fact({ id: "00000000-0000-0000-0000-000000000002", kind: "price", text: "₹35", value: { min: 35, max: 35, currency: "INR" }, volatility: "volatile" }),
    ],
    mapUrl: "https://www.google.com/maps/search/?api=1&query=12.300000%2C76.600000",
    score: 3.2,
    reasons: [{ code: "price_match", text: "Budget-friendly: Listed under “Budget”" }],
    ...overrides,
  };
}

export function hit(overrides: Partial<SearchHit> = {}): SearchHit {
  return {
    id: "00000000-0000-0000-0000-0000000000a1",
    slug: "testville",
    name: "Testville",
    kind: "city",
    description: "city in Test State",
    matchedAlias: null,
    matchType: "exact",
    score: 1,
    stateName: "Test State",
    districtName: "Test District",
    parentCityName: null,
    parentCitySlug: null,
    coverageStatus: "usable",
    latitude: 12.3,
    longitude: 76.6,
    hasGuide: true,
    guideSlug: "testville",
    ...overrides,
  };
}
