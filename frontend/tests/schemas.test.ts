import { describe, expect, it } from "vitest";
import { correctionSchema, fieldErrors, newPlaceSchema, requestSchema } from "@/lib/schemas";

describe("form schemas", () => {
  it("validates destination requests", () => {
    expect(requestSchema.safeParse({ query: "Hampi" }).success).toBe(true);
    const short = requestSchema.safeParse({ query: "a" });
    expect(short.success).toBe(false);
    if (!short.success) expect(fieldErrors(short.error).query).toMatch(/at least 2/);
    expect(requestSchema.safeParse({ query: "1234" }).success).toBe(false);
  });

  it("requires conflict-of-interest details when declared", () => {
    const r = correctionSchema.safeParse({ value: "₹120", conflictOfInterest: true });
    expect(r.success).toBe(false);
    if (!r.success) expect(fieldErrors(r.error).coiDetails).toBeDefined();
    expect(correctionSchema.safeParse({ value: "₹120", conflictOfInterest: true, coiDetails: "I am the owner" }).success).toBe(true);
  });

  it("rejects non-http evidence links", () => {
    const r = correctionSchema.safeParse({ value: "x", evidenceUrl: "javascript:alert(1)" });
    expect(r.success).toBe(false);
    expect(correctionSchema.safeParse({ value: "x", evidenceUrl: "https://example.org/menu" }).success).toBe(true);
  });

  it("validates new places", () => {
    const ok = newPlaceSchema.safeParse({ section: "eat", name: "Vinayaka Mylari", description: "Soft dosas with chutney, cooked on a wood fire." });
    expect(ok.success).toBe(true);
    const bad = newPlaceSchema.safeParse({ section: "eat", name: "V", description: "short" });
    expect(bad.success).toBe(false);
    if (!bad.success) {
      const e = fieldErrors(bad.error);
      expect(e.name).toBeDefined();
      expect(e.description).toBeDefined();
    }
  });
});
