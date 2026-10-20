import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SearchBox } from "@/components/search-box";
import { axeViolations } from "./a11y";
import { hit } from "./fixtures";
import { routerMock } from "./setup";

function mockSearch(results: ReturnType<typeof hit>[]) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = String(input);
    expect(url).toContain("/api/search?q=");
    return new Response(JSON.stringify({ query: "x", results }), { status: 200, headers: { "Content-Type": "application/json" } });
  });
}

describe("SearchBox", () => {
  beforeEach(() => {
    routerMock.push.mockReset();
  });

  it("queries the real search endpoint and lists disambiguated results", async () => {
    const fetchSpy = mockSearch([
      hit(),
      hit({ id: "b", slug: "testville-other-state", stateName: "Other State", kind: "town", matchedAlias: "Testpura", coverageStatus: "indexed" }),
    ]);
    render(<SearchBox />);
    await userEvent.type(screen.getByRole("combobox"), "test");
    const options = await screen.findAllByRole("option");
    expect(options).toHaveLength(2);
    expect(fetchSpy).toHaveBeenCalledWith(expect.stringContaining("q=test"), expect.anything());
    expect(screen.getByText(/Other State/)).toBeInTheDocument();
    expect(screen.getByText("matched “Testpura”")).toBeInTheDocument();
    expect(screen.getByText("No guide yet")).toBeInTheDocument();
  });

  it("supports keyboard navigation and opens the highlighted destination with preferences", async () => {
    mockSearch([hit(), hit({ id: "b", slug: "second", guideSlug: "second", name: "Second" })]);
    render(<SearchBox suffix="food=regional" />);
    const input = screen.getByRole("combobox");
    await userEvent.type(input, "te");
    await screen.findAllByRole("option");
    expect(input).toHaveAttribute("aria-expanded", "true");
    await userEvent.keyboard("{ArrowDown}");
    expect(screen.getAllByRole("option")[1]).toHaveAttribute("aria-selected", "true");
    expect(input.getAttribute("aria-activedescendant")).toBeTruthy();
    await userEvent.keyboard("{Enter}");
    expect(routerMock.push).toHaveBeenCalledWith("/d/second?food=regional");
  });

  it("closes on Escape", async () => {
    mockSearch([hit()]);
    render(<SearchBox />);
    const input = screen.getByRole("combobox");
    await userEvent.type(input, "te");
    await screen.findAllByRole("option");
    await userEvent.keyboard("{Escape}");
    expect(input).toHaveAttribute("aria-expanded", "false");
  });

  it("offers a destination request instead of inventing results", async () => {
    mockSearch([]);
    render(<SearchBox />);
    await userEvent.type(screen.getByRole("combobox"), "Nowhereabad");
    const link = await screen.findByRole("link", { name: /Request “Nowhereabad”/ });
    expect(link).toHaveAttribute("href", "/request?q=Nowhereabad");
    expect(screen.queryAllByRole("option")).toHaveLength(0);
  });

  it("shows an error state when search fails", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 503 }));
    render(<SearchBox />);
    await userEvent.type(screen.getByRole("combobox"), "test");
    expect(await screen.findByText("The guide service is waking up. Try again in a minute.")).toBeInTheDocument();
  });

  it("does not search for a single character", async () => {
    const spy = mockSearch([]);
    render(<SearchBox />);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "t" } });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 300));
    });
    expect(spy).not.toHaveBeenCalled();
  });

  it("has no detectable accessibility violations", async () => {
    mockSearch([hit()]);
    const { container } = render(<SearchBox />);
    await userEvent.type(screen.getByRole("combobox"), "te");
    await waitFor(() => expect(screen.getAllByRole("option")).toHaveLength(1));
    expect(await axeViolations(container)).toEqual([]);
  });
});
