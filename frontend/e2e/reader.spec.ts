import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { GUIDE_SLUG, noHorizontalOverflow } from "./helpers";

test.describe("reader journeys", () => {
  test("search by alternate spelling, open the guide and see provenance @responsive", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: /One place to understand any place in India/ })).toBeVisible();
    const box = page.getByRole("combobox");
    await box.fill("bangalore");
    const option = page.getByRole("option").filter({ hasText: "Bengaluru" }).first();
    await expect(option).toBeVisible();
    await expect(option).toContainText("matched “Bangalore”");
    await box.press("Enter");
    await page.getByRole("button", { name: /Open the Bengaluru guide/ }).click();
    await expect(page).toHaveURL(/\/d\/bengaluru/);
    await expect(page.getByRole("heading", { level: 1, name: "Bengaluru" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Guide sections" })).toBeVisible();
    await noHorizontalOverflow(page);
  });

  test("preferences change the ranking request sent to the API", async ({ page }) => {
    await page.goto(`/d/${GUIDE_SLUG}/stay`);
    const first = page.locator("article h3").first();
    await expect(first).toBeVisible();
    await page.getByRole("button", { name: "Preferences" }).click();
    await page.getByRole("button", { name: "Luxury" }).click();
    await page.getByRole("button", { name: "Apply" }).click();
    await expect(page).toHaveURL(/comfort=luxury/);
    await expect(page.locator("p", { hasText: "Ranked for" })).toContainText("Luxury stays");
    const luxuryFirst = await page.locator("article h3").first().textContent();
    await page.getByRole("button", { name: "Preferences" }).click();
    await page.getByRole("button", { name: "Luxury" }).click();
    await page.getByRole("button", { name: "Basic" }).click();
    await page.getByRole("button", { name: "Apply" }).click();
    await expect(page).toHaveURL(/comfort=basic/);
    await expect(page.locator("article h3").first()).not.toHaveText(luxuryFirst ?? "");
  });

  test("facts show sources, dates and a detail page with evidence", async ({ page }) => {
    await page.goto(`/d/${GUIDE_SLUG}/explore`);
    const firstEntry = page.locator("article").first();
    await expect(firstEntry.getByRole("link", { name: /Wikivoyage/ }).first()).toHaveAttribute("href", /oldid=/);
    await firstEntry.getByRole("link", { name: "Details & history" }).first().click();
    await expect(page.getByRole("heading", { name: "Evidence" })).toBeVisible();
    await expect(page.getByText(/Parsed from a Wikivoyage/).first()).toBeVisible();
    await expect(page.getByRole("heading", { name: "History" })).toBeVisible();
  });

  test("a place without a guide says so instead of showing invented content", async ({ page }) => {
    await page.goto("/search?q=district");
    const indexed = page.locator("li").filter({ hasText: "Indexed, no guide yet" }).first();
    await indexed.getByRole("link").first().click();
    await expect(page.getByText("No guide for this place yet")).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Guide sections" })).toHaveCount(0);
    await expect(page.locator("article")).toHaveCount(0);
  });

  test("request a missing destination without an account", async ({ page }) => {
    const name = `Nowhereabad ${Date.now().toString(36)}`;
    await page.goto(`/search?q=${encodeURIComponent(name)}`);
    await expect(page.getByText(/is not in the index yet/)).toBeVisible();
    await page.getByRole("button", { name: "Request this destination" }).click();
    await expect(page).toHaveURL(/\/requests\/[a-z0-9]+/);
    await expect(page.getByRole("heading", { name: `“${name}”` })).toBeVisible();
    await expect(page.getByRole("status")).toBeVisible();
  });

  test("mobile navigation and guide layout fit small screens @responsive", async ({ page, isMobile }) => {
    test.skip(!isMobile, "mobile only");
    await page.goto(`/d/${GUIDE_SLUG}/eat`);
    await noHorizontalOverflow(page);
    await page.getByRole("button", { name: "Open menu" }).click();
    await expect(page.getByRole("navigation", { name: "Mobile" })).toBeVisible();
    await page.getByRole("link", { name: "How it works" }).click();
    await expect(page.getByRole("heading", { name: "How the information works" })).toBeVisible();
  });

  test("key pages have no serious accessibility violations", async ({ page }) => {
    for (const path of ["/", `/d/${GUIDE_SLUG}`, `/d/${GUIDE_SLUG}/eat`, "/sources", "/request"]) {
      await page.goto(path);
      await page.waitForLoadState("networkidle");
      const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
      const serious = results.violations.filter((v) => v.impact === "serious" || v.impact === "critical");
      expect(serious.map((v) => `${path}: ${v.id} (${v.nodes.length})`)).toEqual([]);
    }
  });
});
