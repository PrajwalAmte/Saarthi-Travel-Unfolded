import { describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));
const { trustedClientIp } = await import("@/lib/proxy");

const req = (xff?: string) => ({ headers: new Headers(xff ? { "x-forwarded-for": xff } : {}) });

describe("trustedClientIp", () => {
  it("ignores X-Forwarded-For unless a trusted proxy is configured", () => {
    expect(trustedClientIp(req("1.1.1.1"), false)).toBeNull();
  });

  it("takes only the hop appended by the trusted proxy, not addresses the client sent", () => {
    // A client claiming 1.1.1.1; the platform load balancer appends the real address.
    expect(trustedClientIp(req("1.1.1.1, 203.0.113.7"), true)).toBe("203.0.113.7");
    expect(trustedClientIp(req("2001:db8::1"), true)).toBe("2001:db8::1");
  });

  it("rejects values that are not addresses", () => {
    expect(trustedClientIp(req("1.1.1.1, <script>"), true)).toBeNull();
    expect(trustedClientIp(req(), true)).toBeNull();
  });
});
