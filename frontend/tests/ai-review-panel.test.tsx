import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { type AiFlag, AiReviewPanel, type AiUsage } from "@/components/ai-review-panel";
import { axeViolations } from "./a11y";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const usage: AiUsage = {
  active: true,
  model: "gemini-flash-lite-latest",
  fallbackModel: "gemini-2.5-flash-lite",
  requestsToday: 12,
  dailyBudget: 200,
  tokensToday: 9000,
  openFlags: 1,
  reviewedListings: 340,
  queuedJobs: 3,
};

const flag: AiFlag = {
  id: "f1",
  entryId: "e1",
  destinationSlug: "new-delhi",
  destinationName: "New Delhi",
  section: "eat",
  title: "Qutub Minar",
  area: null,
  description: "A 73 m tall minaret built in 1193.",
  fitsSection: false,
  inDestination: "yes",
  issues: ["wrong_section"],
  suggestedSection: "see",
  confidence: 0.95,
  reason: "A minaret, not a place to eat.",
  model: "gemini-3.5-flash-lite",
  createdAt: "2026-10-27T00:00:00Z",
};

function mockApi(u: AiUsage, flags: AiFlag[]) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    if (url.endsWith("/usage")) return json(u);
    if (url.endsWith("/publish") && init?.method === "PUT") return json({ published: true, message: "Readers now see AI improvements." });
    if (url.endsWith("/publish")) return json({ published: false });
    if (url.endsWith("/preview"))
      return json({
        slug: "kolkata",
        name: "Kolkata",
        published: false,
        listings: 10,
        enriched: 10,
        summariesAccepted: 1,
        summariesRejected: 1,
        tagged: 1,
        factsExtracted: 1,
        inShort: [{ topic: "best_for", text: "Good for food lovers." }],
        inShortRejected: [{ topic: "when_to_go", text: "Visit in 1234.", reason: "number \"1234\" is not in the original" }],
        entries: [
          { entryId: "a", section: "stay", title: "Seacom Inn YMCA", area: null, original: "An extremely drab and dark hotel...", summary: "An old-fashioned hotel with a heritage café.", summaryStatus: "accepted", summaryRejection: null, tags: [{ tag: "culture", quote: "heritage" }], extracted: [] },
          { entryId: "b", section: "eat", title: "Fable", area: null, original: "Long text", summary: "Founded in 1888.", summaryStatus: "rejected", summaryRejection: "number \"1888\" is not in the original", tags: [], extracted: [{ kind: "price", quote: "Thali ₹180" }] },
        ],
      });
    if (url.includes("/flags?")) return json({ items: flags, page: 0, size: 20, totalItems: flags.length, totalPages: 1 });
    if (init?.method === "POST") return json({ message: "The listing is hidden from the guide." });
    return json({});
  });
}

describe("AiReviewPanel", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-token";
  });

  it("shows quota use and each flag with the reviewer's reason", async () => {
    mockApi(usage, [flag]);
    render(<AiReviewPanel />);
    expect(await screen.findByText("Qutub Minar")).toBeInTheDocument();
    expect(screen.getByText(/12/)).toBeInTheDocument();
    expect(screen.getByText("wrong section")).toBeInTheDocument();
    expect(screen.getByText(/A minaret, not a place to eat/)).toBeInTheDocument();
    expect(screen.getByText(/95% sure, belongs under see/)).toBeInTheDocument();
  });

  it("hides a listing only when the admin agrees", async () => {
    const spy = mockApi(usage, [flag]);
    render(<AiReviewPanel />);
    await userEvent.click(await screen.findByRole("button", { name: "Hide listing" }));
    await waitFor(() => expect(spy.mock.calls.some(([u, i]) => String(u).endsWith("/flags/f1/accept") && i?.method === "POST")).toBe(true));
    expect(await screen.findByText("The listing is hidden from the guide.")).toBeInTheDocument();
  });

  it("explains how to switch it on when it is off", async () => {
    mockApi({ ...usage, active: false }, []);
    render(<AiReviewPanel />);
    expect(await screen.findByText("AI review is off")).toBeInTheDocument();
    expect(screen.getByText("No open flags.")).toBeInTheDocument();
  });

  it("is accessible", async () => {
    mockApi(usage, [flag]);
    const { container } = render(<AiReviewPanel />);
    await screen.findByText("Qutub Minar");
    expect(await axeViolations(container)).toEqual([]);
  });

  it("publishes to readers only when the admin switches it on", async () => {
    const spy = mockApi(usage, []);
    render(<AiReviewPanel />);
    await userEvent.click(await screen.findByRole("button", { name: "Show to readers" }));
    await waitFor(() =>
      expect(spy.mock.calls.some(([u, i]) => String(u).endsWith("/ai-review/publish") && i?.method === "PUT" && String(i.body).includes('"published":true'))).toBe(true),
    );
  });

  it("previews before and after, including what the checks rejected", async () => {
    mockApi(usage, []);
    render(<AiReviewPanel />);
    await userEvent.type(await screen.findByPlaceholderText("new-delhi"), "kolkata");
    await userEvent.click(screen.getByRole("button", { name: "Show before and after" }));
    expect(await screen.findByText("An old-fashioned hotel with a heritage café.")).toBeInTheDocument();
    expect(screen.getByText(/Rejected: number "1888"/)).toBeInTheDocument();
    expect(screen.getByText(/rejected: number "1234"/)).toBeInTheDocument();
    expect(screen.getByText(/price: “Thali ₹180”/)).toBeInTheDocument();
  });
});
