import { describe, expect, it } from "vitest";
import { siteUrl } from "@/lib/site-url";

describe("siteUrl", () => {
  it("uses the configured address", () => {
    expect(siteUrl({ NEXT_PUBLIC_SITE_URL: "https://saarthi.example.test" }).origin).toBe("https://saarthi.example.test");
    expect(siteUrl({ SITE_URL: "https://runtime.example.test", NEXT_PUBLIC_SITE_URL: "https://build.example.test" }).origin).toBe(
      "https://runtime.example.test",
    );
  });

  it("falls back instead of failing when the value is missing, empty or malformed", () => {
    expect(siteUrl({}).origin).toBe("http://localhost:3000");
    expect(siteUrl({ NEXT_PUBLIC_SITE_URL: "" }).origin).toBe("http://localhost:3000");
    expect(siteUrl({ NEXT_PUBLIC_SITE_URL: "   " }).origin).toBe("http://localhost:3000");
    expect(siteUrl({ NEXT_PUBLIC_SITE_URL: "not a url", SITE_URL: "" }).origin).toBe("http://localhost:3000");
    expect(siteUrl({ SITE_URL: "bad", NEXT_PUBLIC_SITE_URL: "https://ok.example.test" }).origin).toBe("https://ok.example.test");
  });
});
