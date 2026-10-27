import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import RecheckPage from "@/app/d/[slug]/recheck/page";
import type { RecheckView } from "@/lib/types";
import { axeViolations } from "./a11y";
import { fact } from "./fixtures";

const getDestination = vi.fn();
const apiGetOrNull = vi.fn();

vi.mock("@/lib/destination", () => ({ getDestination: (...a: unknown[]) => getDestination(...a) }));
vi.mock("@/lib/server-api", () => ({ apiGetOrNull: (...a: unknown[]) => apiGetOrNull(...a) }));
vi.mock("next/navigation", async (orig) => ({ ...(await orig<object>()), notFound: () => { throw new Error("not found"); } }));
vi.mock("@/components/session-provider", () => ({
  useSession: () => ({ session: { authenticated: false, user: null, emailSignInAvailable: true, oauthProviders: [], csrfToken: null }, loading: false, refresh: vi.fn(), signOut: vi.fn() }),
}));

function view(overrides: Partial<RecheckView> = {}): RecheckView {
  const stale = (id: string, kind: "price" | "hours", text: string, observedAt: string | null) =>
    fact({ id, kind, text, observedAt, freshness: "due", verificationStatus: "needs_reverification", volatility: "volatile" });
  return {
    slug: "testville",
    name: "Testville",
    totalDue: 40,
    shown: 3,
    dueBySection: { eat: 20, stay: 10, explore: 10 },
    items: [
      { section: "eat", rank: 1, entryId: "e1", title: "Hotel Example", area: "Station Road", mapUrl: "https://www.google.com/maps/search/?api=1&query=Hotel", mapsToBudget: true, fact: stale("f1", "price", "₹35", "2021-03-26") },
      { section: "eat", rank: 2, entryId: "e2", title: "Lake View Restaurant", area: null, mapUrl: "https://www.google.com/maps/search/?api=1&query=Lake", mapsToBudget: false, fact: stale("f2", "hours", "noon-11PM", null) },
      { section: "stay", rank: 1, entryId: "e3", title: "Palace Inn", area: null, mapUrl: "https://www.google.com/maps/search/?api=1&query=Palace", mapsToBudget: true, fact: stale("f3", "price", "₹2500", "2019-05-01") },
    ],
    ...overrides,
  };
}

async function renderPage() {
  render(await RecheckPage({ params: Promise.resolve({ slug: "testville" }) }));
}

describe("Help check this guide", () => {
  beforeEach(() => {
    getDestination.mockReset().mockResolvedValue({ id: "dest-1", slug: "testville", name: "Testville" });
    apiGetOrNull.mockReset().mockResolvedValue(view());
  });

  it("lists stale prices and hours grouped by tab, in listing order, with a way to confirm each", async () => {
    await renderPage();
    expect(screen.getByRole("heading", { name: "Help check Testville" })).toBeInTheDocument();
    expect(screen.getByText(/40 prices and hours here need a check/)).toBeInTheDocument();
    const eat = screen.getByRole("region", { name: "Places to eat" });
    const rows = within(eat).getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("Hotel Example");
    expect(rows[0]).toHaveTextContent("last recorded Mar 2021");
    expect(rows[1]).toHaveTextContent("no date recorded");
    expect(screen.getByRole("region", { name: "Places to stay" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Things to see and do" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /Still true\?/ })).toHaveLength(3);
  });

  it("says so when nothing needs checking", async () => {
    apiGetOrNull.mockResolvedValue(view({ items: [], shown: 0, totalDue: 0 }));
    await renderPage();
    expect(screen.getByText("Nothing is waiting to be checked")).toBeInTheDocument();
  });

  it("is not found for an unknown destination", async () => {
    getDestination.mockResolvedValue(null);
    await expect(renderPage()).rejects.toThrow("not found");
  });

  it("has no detectable accessibility violations", async () => {
    await renderPage();
    expect(await axeViolations(document.body)).toEqual([]);
  });
});
