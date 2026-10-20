import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
const { apiGet, ApiError } = await import("@/lib/server-api");

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

describe("apiGet", () => {
  afterEach(() => vi.restoreAllMocks());

  it("retries once after a network error or timeout, then succeeds", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockRejectedValueOnce(new Error("aborted")).mockResolvedValueOnce(json({ ok: 1 }));
    await expect(apiGet("/api/meta")).resolves.toEqual({ ok: 1 });
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });

  it("retries once after a 502/503/504 from a service that is waking up", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(json({}, 502)).mockResolvedValueOnce(json({ ok: 1 }));
    await expect(apiGet("/api/meta")).resolves.toEqual({ ok: 1 });
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });

  it("does not retry real errors such as 404", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(json({ detail: "nope" }, 404));
    await expect(apiGet("/api/destinations/none")).rejects.toMatchObject({ status: 404 });
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it("gives up after the single retry", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(json({}, 503));
    await expect(apiGet("/api/meta")).rejects.toBeInstanceOf(ApiError);
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });
});
