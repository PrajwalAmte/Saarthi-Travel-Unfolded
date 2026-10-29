import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { IndexedOnly } from "@/components/guide/indexed-only";
import { type DestinationDetail, isHub } from "@/lib/types";

function detail(overrides: Partial<DestinationDetail>): DestinationDetail {
  return {
    id: "d",
    slug: "goa",
    name: "Goa",
    kind: "state",
    latitude: 15.4,
    longitude: 74.0,
    summary: null,
    childDestinations: [
      { slug: "panaji", name: "Panaji", kind: "city", coverageStatus: "usable" },
      { slug: "old-goa", name: "Old Goa", kind: "town", coverageStatus: "building" },
    ],
    guidedPlaces: 15,
    ...overrides,
  } as DestinationDetail;
}

describe("state pages", () => {
  it("present a state with guided places as a hub, not as a missing guide", () => {
    render(<IndexedOnly d={detail({})} />);
    expect(screen.getByText(/Goa is a state, so its guide is made of guides for the places in it/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Guides in Goa" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Ask for a Goa guide/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/no written guide/)).not.toBeInTheDocument();
  });

  it("still offers a request for a place with no guide", () => {
    render(<IndexedOnly d={detail({ name: "Sirsi", slug: "sirsi", kind: "town", guidedPlaces: 0, childDestinations: [] })} />);
    expect(screen.getByRole("link", { name: "Ask for a Sirsi guide" })).toBeInTheDocument();
  });

  it("only treats states and union territories with guided places as hubs", () => {
    expect(isHub({ kind: "state", guidedPlaces: 3 })).toBe(true);
    expect(isHub({ kind: "union_territory", guidedPlaces: 1 })).toBe(true);
    expect(isHub({ kind: "state", guidedPlaces: 0 })).toBe(false);
    expect(isHub({ kind: "city", guidedPlaces: 9 })).toBe(false);
  });
});
