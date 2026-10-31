import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { GuideTabs } from "@/components/guide/guide-tabs";
import { tripOrigins, weekendTripCount } from "@/lib/weekend-trips";

const apiGet = vi.fn();

vi.mock("server-only", () => ({}));
vi.mock("@/lib/server-api", () => ({ apiGet: (...a: unknown[]) => apiGet(...a) }));
vi.mock("next/navigation", () => ({ usePathname: () => "/d/bengaluru", useSearchParams: () => new URLSearchParams() }));

describe("Guide tabs", () => {
  it("call a city's parts Areas, not Neighbourhoods", () => {
    render(<GuideTabs slug="bengaluru" counts={{ neighbourhoods: 5 }} />);
    expect(screen.getByRole("link", { name: /^Areas/ })).toHaveAttribute("href", "/d/bengaluru/neighbourhoods");
    expect(screen.queryByText("Neighbourhoods")).not.toBeInTheDocument();
  });

  it("link to weekend trips only when the place has a weekend-trips page", () => {
    const { rerender } = render(<GuideTabs slug="bengaluru" counts={{}} weekendTrips={80} />);
    expect(screen.getByRole("link", { name: /Weekend trips/ })).toHaveAttribute("href", "/trips/weekend-from/bengaluru");
    rerender(<GuideTabs slug="mysuru" counts={{}} />);
    expect(screen.queryByRole("link", { name: /Weekend trips/ })).not.toBeInTheDocument();
  });
});

describe("Weekend trip origins", () => {
  beforeEach(() => {
    apiGet.mockReset().mockImplementation(async (path: string) => {
      if (path === "/api/trips/origins") return [{ slug: "bengaluru", name: "Bengaluru", radiusKm: 350 }, { slug: "sirsi", name: "Sirsi", radiusKm: 200 }];
      if (path.endsWith("/bengaluru")) return { total: 80, maxKm: 350 };
      if (path.endsWith("/sirsi")) return { total: 2, maxKm: 200 };
      throw new Error("unexpected " + path);
    });
  });

  it("keep only origins with enough guided places around them", async () => {
    expect(await tripOrigins()).toEqual([{ slug: "bengaluru", name: "Bengaluru", radiusKm: 350, total: 80, maxKm: 350 }]);
    expect(await weekendTripCount("bengaluru")).toBe(80);
    expect(await weekendTripCount("sirsi")).toBeNull();
  });

  it("show nothing when the API is unavailable", async () => {
    apiGet.mockRejectedValue(new Error("down"));
    expect(await tripOrigins()).toEqual([]);
  });
});
