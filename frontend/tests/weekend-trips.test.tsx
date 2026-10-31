import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import WeekendTripsPage, { generateMetadata } from "@/app/trips/weekend-from/[slug]/page";
import { driveTime, monthRanges } from "@/lib/trips";
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
    roadKm: null,
    driveMinutes: null,
    coverageStatus: "usable",
    placesToEat: 4,
    placesToStay: 2,
    thingsToDo: 6,
    knownFor: null,
    knownForSource: null,
    gettingThere: null,
    gettingThereSource: null,
    airport: null,
    station: null,
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
    bandsBy: "distance",
    routesComputedAt: null,
    bands: [
      {
        key: "km-150",
        label: "Up to 150 km",
        trips: [
          trip("mysuru", 127, {
            knownFor: "Mysuru is known for its palace.",
            knownForSource: { name: "Wikivoyage", url: "https://en.wikivoyage.org/wiki/Mysore" },
            gettingThere: "Trains run from Bengaluru every hour.",
            gettingThereSource: { name: "Wikivoyage", url: "https://en.wikivoyage.org/wiki/Mysore" },
            comfortableMonths: [11, 12, 1, 2],
            airport: { name: "Mysore Airport", code: "MYQ", distanceKm: 10 },
            station: { name: "Mysuru Junction", code: null, distanceKm: 2 },
            climatePeriod: "1991-2020",
          }),
          trip("ramanagara", 50, { comfortableMonths: [], climatePeriod: "1991-2020", placesToStay: 0 }),
        ],
      },
      { key: "km-250", label: "150 to 250 km", trips: [trip("hassan", 180), trip("madikeri", 220)] },
      { key: "km-350", label: "250 to 350 km", trips: [trip("ooty", 270, { roadKm: 330, driveMinutes: 392 })] },
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
    expect(first).toHaveTextContent("Nearest airport: MYQ (10 km) · Railway station: Mysuru Junction (2 km)");
    expect(within(first).getAllByRole("link", { name: "Wikivoyage" })[0]).toHaveAttribute("href", "https://en.wikivoyage.org/wiki/Mysore");
    expect(within(first).getByRole("link", { name: /Open the guide/ })).toHaveAttribute("href", "/d/mysuru");
    expect(second).toHaveTextContent("No month is both mild and mostly dry");
    expect(second).not.toHaveTextContent("to stay");
    expect(screen.getByText("Distances are in a straight line, not by road.")).toBeInTheDocument();
    expect(screen.getByText(/about 6 h 30 min by road \(330 km\)/)).toBeInTheDocument();
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

describe("driveTime", () => {
  it("rounds to five minutes and reads naturally", () => {
    expect(driveTime(43)).toBe("45 min");
    expect(driveTime(180)).toBe("3 h");
    expect(driveTime(192)).toBe("3 h 10 min");
    expect(driveTime(1)).toBe("5 min");
  });
});
