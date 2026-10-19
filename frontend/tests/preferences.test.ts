import { describe, expect, it } from "vitest";
import { hasPreferences, parsePreferences, preferencesToParams } from "@/lib/preferences";

describe("preferences", () => {
  it("parses valid preferences from search params", () => {
    const p = parsePreferences(new URLSearchParams("food=regional&budget=budget&comfort=luxury&interests=culture,nature"));
    expect(p).toEqual({ food: "regional", budget: "budget", comfort: "luxury", interests: ["culture", "nature"] });
    expect(hasPreferences(p)).toBe(true);
  });

  it("drops invalid values instead of failing", () => {
    const p = parsePreferences({ food: "spicy", budget: "premium", interests: "culture,hacking,food" });
    expect(p.food).toBeUndefined();
    expect(p.budget).toBe("premium");
    expect(p.interests).toEqual(["culture", "food"]);
  });

  it("limits interests to three", () => {
    const p = parsePreferences(new URLSearchParams("interests=food,culture,nature,adventure"));
    expect(p.interests).toHaveLength(3);
  });

  it("serialises to the API query parameters", () => {
    const params = preferencesToParams({ food: "popular", budget: "moderate", interests: ["family", "shopping"] });
    expect(params.toString()).toBe("food=popular&budget=moderate&interests=family%2Cshopping");
    expect(preferencesToParams({ interests: [] }).toString()).toBe("");
  });

  it("treats empty preferences as none", () => {
    expect(hasPreferences(parsePreferences(new URLSearchParams("")))).toBe(false);
  });
});
