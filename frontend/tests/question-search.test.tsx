import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { QuestionResults } from "@/components/question-results";
import { SearchBox } from "@/components/search-box";
import { looksLikeQuestion } from "@/lib/question";
import type { QuestionAnswer } from "@/lib/types";
import { axeViolations } from "./a11y";

function respond(body: unknown) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(
    async () => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } }),
  );
}

const answer: QuestionAnswer = {
  query: "hill station within 4 hours from Bangalore in April, somewhere quiet",
  mode: "question",
  understood: ["From Bengaluru", "Up to 4 h by road", "Mild in April", "Hill stations"],
  unsupported: ["quiet"],
  notes: [],
  origin: { slug: "bengaluru", name: "Bengaluru" },
  results: [
    {
      slug: "yercaud",
      name: "Yercaud",
      kind: "town",
      state: "Tamil Nadu",
      distanceKm: 160,
      driveMinutes: 230,
      coverageStatus: "usable",
      imageUrl: null,
      reasons: [
        { text: "About 3 h 50 min from Bengaluru by road", sourceName: "OSRM / OpenStreetMap", sourceUrl: null },
        { text: "“Yercaud is a hill station in the Shevaroy Hills.”", sourceName: "English Wikivoyage", sourceUrl: "https://en.wikivoyage.org/wiki/Yercaud" },
        { text: "Usually mild in April: average high 28°C, 3 rainy days (2016–2025 averages)", sourceName: "Open-Meteo", sourceUrl: null },
      ],
    },
  ],
};

describe("Natural-language search", () => {
  it("tells questions from place names", () => {
    expect(looksLikeQuestion("hill station within 4 hours from Bangalore")).toBe(true);
    expect(looksLikeQuestion("beach near Mumbai")).toBe(true);
    expect(looksLikeQuestion("Mysuru")).toBe(false);
    expect(looksLikeQuestion("Statue of Unity")).toBe(false);
  });

  it("shows how the question was read and why each place matched, with sources", async () => {
    respond(answer);
    const { container } = render(
      <QuestionResults q={answer.query}>
        <p>name results</p>
      </QuestionResults>,
    );
    expect(screen.getByRole("status")).toHaveTextContent("Reading your question");
    expect(await screen.findByRole("heading", { name: "How Saarthi read your question" })).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Filters" })).toHaveTextContent("From BengaluruUp to 4 h by roadMild in AprilHill stations");
    expect(screen.getByText(/can't filter by “quiet” yet/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Yercaud" })).toHaveAttribute("href", "/d/yercaud");
    expect(screen.getByRole("link", { name: "English Wikivoyage" })).toHaveAttribute("href", "https://en.wikivoyage.org/wiki/Yercaud");
    expect(screen.getByText(/Usually mild in April/)).toBeInTheDocument();
    expect(screen.getByText(/An AI model only turned your words into these filters/)).toBeInTheDocument();
    expect(screen.queryByText("name results")).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it("falls back to name search when the question cannot be read", async () => {
    respond({ ...answer, mode: "unavailable", understood: [], results: [] });
    render(
      <QuestionResults q="beach near Mumbai">
        <p>name results</p>
      </QuestionResults>,
    );
    expect(await screen.findByText("name results")).toBeInTheDocument();
    expect(screen.getByText(/could not read this as a question right now/)).toBeInTheDocument();
  });

  it("offers to find places for a question instead of requesting it as a destination", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(
      async () => new Response(JSON.stringify({ query: "x", results: [] }), { status: 200, headers: { "Content-Type": "application/json" } }),
    );
    render(<SearchBox />);
    await userEvent.type(screen.getByRole("combobox"), "beach near Mumbai");
    expect(await screen.findByRole("link", { name: /Find places for “beach near Mumbai”/ })).toBeInTheDocument();
    expect(screen.queryByText(/as a destination/)).not.toBeInTheDocument();
  });
});
