import { describe, expect, it } from "vitest";
import { isWaiting } from "@/components/admin-console";

describe("job status", () => {
  it("shows paused jobs as waiting, not as errors", () => {
    expect(isWaiting({ status: "queued", lastError: "Waiting: Today's AI request budget of 200 is used up" })).toBe(true);
    expect(isWaiting({ status: "queued", lastError: "Waiting: the source asked us to slow down (HTTP 429), trying again in an hour" })).toBe(true);
    expect(isWaiting({ status: "queued", lastError: "FetchException: HTTP 500 from example.org" })).toBe(false);
    expect(isWaiting({ status: "failed", lastError: "Waiting: x" })).toBe(false);
    expect(isWaiting({ status: "queued", lastError: null })).toBe(false);
  });
});
