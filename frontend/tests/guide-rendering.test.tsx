import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { CoverageBadge } from "@/components/coverage-badge";
import { BudgetTable } from "@/components/guide/budget-table";
import { EntryCard } from "@/components/guide/entry-card";
import { FactMeta } from "@/components/guide/fact-meta";
import { SectionList } from "@/components/guide/section-list";
import type { SectionView } from "@/lib/types";
import { axeViolations } from "./a11y";
import { entry, fact } from "./fixtures";

vi.mock("@/components/session-provider", () => ({
  useSession: () => ({ session: { authenticated: false, user: null, emailSignInAvailable: true, oauthProviders: [], csrfToken: null }, loading: false, refresh: vi.fn(), signOut: vi.fn() }),
}));

function section(overrides: Partial<SectionView> = {}): SectionView {
  return {
    section: "eat",
    categories: [],
    entries: { items: [], page: 0, size: 12, totalItems: 0, totalPages: 0 },
    totalPublished: 0,
    preferencesApplied: false,
    personalizationApplied: false,
    personalizationNote: null,
    excludedByConstraints: 0,
    constraintNotes: [],
    notes: [],
    specialities: [],
    externalLinks: [],
    ...overrides,
  };
}

describe("fact provenance", () => {
  it("shows the source, its date and the actions, without noise for unconfirmed facts", () => {
    render(<FactMeta fact={fact()} destinationId="d" />);
    const link = screen.getByRole("link", { name: /English Wikivoyage/ });
    expect(link).toHaveTextContent("Mar 2021");
    expect(link).toHaveAttribute("href", "https://en.wikivoyage.org/w/index.php?title=Testville&oldid=1");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
    expect(screen.getByRole("link", { name: "Details" })).toHaveAttribute("href", "/facts/00000000-0000-0000-0000-000000000001");
    // Absence of confirmations is not shown as a warning; the full status is on the details page.
    expect(screen.queryByText(/No community verifications/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Confirmed by/)).not.toBeInTheDocument();
  });

  it("shows local confirmations and reported changes distinctly", () => {
    const { rerender } = render(
      <FactMeta fact={fact({ verificationStatus: "community_verified", verificationCount: 3, lastVerifiedAt: "2026-09-12T00:00:00Z" })} destinationId="d" />,
    );
    expect(screen.getByText(/Confirmed by 3 locals/)).toHaveTextContent(/Sep(t)? 2026/);
    rerender(<FactMeta fact={fact({ verificationStatus: "conflicting_reports", denialCount: 1 })} destinationId="d" />);
    expect(screen.getByText(/Reported as changed — check before you go/)).toBeInTheDocument();
  });

  it("never invents a date it does not have", () => {
    render(<FactMeta fact={fact({ observedAt: null, observedBasis: "unknown", verificationStatus: "source_backed", volatility: "stable" })} destinationId="d" />);
    const link = screen.getByRole("link", { name: /English Wikivoyage/ });
    expect(link.textContent).not.toMatch(/\d{4}/);
  });

  it("does not render unsafe source links", () => {
    const bad = fact();
    bad.evidence = [{ ...bad.evidence[0]!, permalink: "javascript:alert(1)", url: "javascript:alert(1)" }];
    render(<FactMeta fact={bad} destinationId="d" />);
    expect(screen.queryByRole("link", { name: /English Wikivoyage/ })).not.toBeInTheDocument();
    expect(screen.getByText(/English Wikivoyage/)).toBeInTheDocument();
  });
});

describe("EntryCard", () => {
  it("renders labels with their basis, details, map link and ranking reasons", async () => {
    const { container } = render(<EntryCard entry={entry()} destinationId="d" destinationSlug="testville" rank={1} />);
    expect(screen.getByRole("heading", { name: /Test Tiffin Room/ })).toBeInTheDocument();
    expect(screen.getByText("Budget-friendly")).toHaveAttribute("title", "Listed under “Budget”");
    expect(screen.getByText("₹35")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Map" })).toHaveAttribute("href", expect.stringContaining("google.com/maps"));
    expect(screen.getByText("Why it's listed")).toBeInTheDocument();
    expect(screen.getByText("Budget-friendly: Listed under “Budget”")).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it("shows no description rather than inventing one", () => {
    render(<EntryCard entry={entry({ description: null, reasons: [] })} destinationId="d" destinationSlug="testville" />);
    expect(screen.getByRole("heading", { name: /Test Tiffin Room/ })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Details" })).not.toBeInTheDocument();
    expect(screen.queryByText("Why it's listed")).not.toBeInTheDocument();
  });
});

describe("coverage and section states", () => {
  it("labels coverage in terms of what a traveller gets", () => {
    const { rerender } = render(<CoverageBadge status="indexed" />);
    expect(screen.getByText("No guide yet")).toBeInTheDocument();
    rerender(<CoverageBadge status="building" />);
    expect(screen.getByText("Guide in progress")).toBeInTheDocument();
    rerender(<CoverageBadge status="needs_refresh" />);
    expect(screen.getByText("Guide")).toBeInTheDocument();
    rerender(<CoverageBadge status="well_covered" />);
    expect(screen.getByText("Full guide")).toBeInTheDocument();
  });

  it("shows an empty state with a way to contribute when nothing is published", () => {
    render(<SectionList data={section()} slug="testville" destinationId="d" basePath="/d/testville/eat" params={new URLSearchParams()} />);
    expect(screen.getByText("No places to eat have been published for this destination yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Suggest it" })).toHaveAttribute("href", "/contribute/testville");
  });

  it("explains when preferences could not personalise and when constraints hid entries", () => {
    render(
      <SectionList
        data={section({
          totalPublished: 2,
          preferencesApplied: true,
          personalizationNote: "Only 0 of 2 entries have recorded attributes that match your preferences.",
          constraintNotes: ["1 hidden because no price information is recorded for them."],
          entries: { items: [entry()], page: 0, size: 12, totalItems: 1, totalPages: 1 },
        })}
        slug="testville"
        destinationId="d"
        basePath="/d/testville/eat"
        params={new URLSearchParams("budget=budget")}
      />,
    );
    expect(screen.getByText(/Only 0 of 2 entries/)).toBeInTheDocument();
    expect(screen.getByText(/no price information/)).toBeInTheDocument();
  });

  it("paginates with links that keep preferences", () => {
    render(
      <SectionList
        data={section({ totalPublished: 30, entries: { items: [entry()], page: 0, size: 12, totalItems: 30, totalPages: 3 } })}
        slug="testville"
        destinationId="d"
        basePath="/d/testville/eat"
        params={new URLSearchParams("food=regional")}
      />,
    );
    const nav = screen.getByRole("navigation", { name: "Pages" });
    expect(within(nav).getByRole("link", { name: /Next/ })).toHaveAttribute("href", "/d/testville/eat?food=regional&page=1");
  });

  it("shows budget assumptions or an honest empty state", () => {
    const { rerender } = render(
      <BudgetTable
        slug="testville"
        data={{ currency: "INR", ranges: [], assumptions: [], priceFactsConsidered: 0, priceFactsWithoutAmount: 0, emptyReason: "No prices have been recorded for this destination yet." }}
      />,
    );
    expect(screen.getByText(/No prices have been recorded/)).toBeInTheDocument();
    rerender(
      <BudgetTable
        slug="testville"
        data={{
          currency: "INR",
          ranges: [{ category: "meals", label: "Food", tier: null, low: 50, median: 80, high: 150, observations: 5, earliestObserved: "2021-01-01", latestObserved: "2025-01-01", needingReverification: 4, unit: "per dish or meal, as listed", factIds: [] }],
          assumptions: ["Figures come from prices written in guide listings."],
          priceFactsConsidered: 5,
          priceFactsWithoutAmount: 0,
          emptyReason: null,
        }}
      />,
    );
    expect(screen.getByText("₹50 – ₹150")).toBeInTheDocument();
    expect(screen.getByText("4 may be dated")).toBeInTheDocument();
    expect(screen.getByText("Figures come from prices written in guide listings.")).toBeInTheDocument();
  });
});
