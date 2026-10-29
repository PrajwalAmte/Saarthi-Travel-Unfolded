import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import WeekendTripsPage, { generateMetadata } from "@/app/trips/weekend-from/[slug]/page";
import { monthRanges } from "@/lib/trips";
import type { Trip, WeekendTrips } from "@/lib/types";
import { axeViolations } from "./a11y";

const apiGetOrNull = vi.fn();

vi.mock("@/lib/server-api", () => ({ apiGetOrNull: (...a: unknown[]) => apiGetOrNull(...a) }));
vi.mock("next/navigation", async (orig) => ({ ...(await orig<object>()), notFound: () => { throw new Error("not found"); } }));

function trip(slug: string, distanceKm: number, overrides: Partial<Trip> = {}): Trip {
  return {
    slug,
    name: slug.charAt(0).toUpperCase() + slug.slice(1),
    kind: "town",
    state: "Karnataka",
    distanceKm,
    coverageStatus: "usable",
    placesToEat: 4,
    placesToStay: 2,
    thingsToDo: 6,
    knownFor: null,
    knownForSource: null,
    gettingThere: null,
    gettingThereSource: null,
    comfortableMonths: [],
    climatePeriod: null,
    imageUrl: null,
    imageCredit: null,
    ...overrides,
  };
}

function view(overrides: Partial<WeekendTrips> = {}): WeekendTrips {
  return {
    originSlug: "bengaluru",
    originName: "Bengaluru",
    maxKm: 350,
    total: 5,
    generatedAt: "2026-10-29T10:00:00Z",
    bands: [
      {
        label: "Up to 150 km",
        fromKm: 30,
        toKm: 150,
        trips: [
          trip("mysuru", 127, {
            knownFor: "Mysuru is known for its palace.",
            knownForSource: { name: "Wikivoyage", url: "https://en.wikivoyage.org/wiki/Mysore" },
            gettingThere: "Trains run from Bengaluru every hour.",
            gettingThereSource: { name: "Wikivoyage", url: "https://en.wikivoyage.org/wiki/Mysore" },
            comfortableMonths: [11, 12, 1, 2],
            climatePeriod: "1991-2020",
          }),
          trip("ramanagara", 50, { comfortableMonths: [], climatePeriod: "1991-2020", placesToStay: 0 }),
        ],
      },
      { label: "150 to 250 km", fromKm: 150, toKm: 250, trips: [trip("hassan", 180), trip("madikeri", 220)] },
      { label: "250 to 350 km", fromKm: 250, toKm: 350, trips: [trip("ooty", 270)] },
    ],
    method: ["Distances are in a straight line, not by road."],
    ...overrides,
  };
}

describe("Weekend trips page", () => {
  beforeEach(() => apiGetOrNull.mockReset().mockResolvedValue(view()));

  it("lists places by distance band with sourced notes, mild months and a link to each guide", async () => {
    const { container } = render(await WeekendTripsPage({ params: Promise.resolve({ slug: "bengaluru" }) }));
    expect(screen.getByRole("heading", { level: 1, name: "Weekend trips from Bengaluru" })).toBeInTheDocument();
    const near = screen.getByRole("region", { name: /Up to 150 km/ });
    const [first, second] = within(near).getAllByRole("listitem") as [HTMLElement, HTMLElement];
    expect(within(near).getAllByRole("listitem")).toHaveLength(2);
    expect(first).toHaveTextContent("Mysuru is known for its palace.");
    expect(first).toHaveTextContent("about 127 km in a straight line");
    expect(first).toHaveTextContent("Mildest months: Nov–Feb");
    expect(within(first).getAllByRole("link", { name: "Wikivoyage" })[0]).toHaveAttribute("href", "https://en.wikivoyage.org/wiki/Mysore");
    expect(within(first).getByRole("link", { name: /Open the guide/ })).toHaveAttribute("href", "/d/mysuru");
    expect(second).toHaveTextContent("No month is both mild and mostly dry");
    expect(second).not.toHaveTextContent("to stay");
    expect(screen.getByText("Distances are in a straight line, not by road.")).toBeInTheDocument();
    const ld = [...container.querySelectorAll('script[type="application/ld+json"]')].map((s) => JSON.parse(s.textContent ?? "{}"));
    expect(ld.find((d) => d["@type"] === "ItemList")?.numberOfItems).toBe(5);
    expect(await axeViolations(container)).toEqual([]);
  });

  it("is not shown or indexed while too few guides exist", async () => {
    apiGetOrNull.mockResolvedValue(view({ total: 2 }));
    await expect(WeekendTripsPage({ params: Promise.resolve({ slug: "bengaluru" }) })).rejects.toThrow("not found");
    expect((await generateMetadata({ params: Promise.resolve({ slug: "bengaluru" }) })).robots).toMatchObject({ index: false });
  });

  it("rejects malformed slugs without calling the API", async () => {
    await expect(WeekendTripsPage({ params: Promise.resolve({ slug: "../admin" }) })).rejects.toThrow("not found");
    expect(apiGetOrNull).not.toHaveBeenCalled();
  });

  it("has a canonical URL and a title naming the count and radius", async () => {
    const meta = await generateMetadata({ params: Promise.resolve({ slug: "bengaluru" }) });
    expect(meta.title).toBe("Weekend trips from Bengaluru: 5 places within 350 km");
    expect(meta.alternates?.canonical).toBe("/trips/weekend-from/bengaluru");
  });
});

describe("monthRanges", () => {
  it("joins consecutive months, wrapping round the year", () => {
    expect(monthRanges([11, 12, 1, 2])).toBe("Nov–Feb");
    expect(monthRanges([1, 3])).toBe("Jan, Mar");
    expect(monthRanges([10, 11, 12, 1, 2, 3, 6])).toBe("Jun, Oct–Mar");
    expect(monthRanges([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])).toBe("All year");
    expect(monthRanges([])).toBe("");
  });
});
