import { expect, test } from "@playwright/test";
import { ADMIN_EMAIL, GUIDE_SLUG, signIn } from "./helpers";

test.describe("contributor journeys", () => {
  test("verification creates a real record and moderation publishes a correction", async ({ browser }) => {
    const stamp = Date.now().toString(36);
    const contributor = await (await browser.newContext()).newPage();
    await signIn(contributor, `e2e-${stamp}@example.test`, `E2E ${stamp}`);

    // A "Still true?" confirmation of a stable/semi-stable fact counts immediately.
    await contributor.goto(`/d/${GUIDE_SLUG}/explore`);
    const entry = contributor.locator("article").nth(3);
    const title = (await entry.locator("h3").textContent())?.replace(/^\d+\.\s*/, "").trim() ?? "";
    await entry.getByRole("button", { name: /Still true\?/ }).first().click();
    await contributor.getByText("Yes, still true").click();
    await contributor.getByRole("button", { name: "Submit" }).click();
    await expect(contributor.getByText(/Your confirmation is recorded|already responded/)).toBeVisible();
    await contributor.getByRole("button", { name: "Close" }).first().click();

    // A correction from a new contributor is held for review.
    await contributor.reload();
    const target = contributor.locator("article").filter({ hasText: title }).first();
    await target.getByRole("button", { name: /Still true\?/ }).first().click();
    await contributor.getByText("It has changed").click();
    const licence = contributor.getByRole("button", { name: /I agree to license my contributions/ });
    if (await licence.isVisible()) await licence.click();
    const newValue = `Updated by end-to-end test ${stamp}: open daily, check the official site before visiting.`;
    await contributor.getByLabel("What is it now?").fill(newValue);
    await contributor.getByRole("button", { name: "Submit" }).click();
    await expect(contributor.getByText(/will review this before it is published/)).toBeVisible();

    // An administrator reviews and accepts it.
    const admin = await (await browser.newContext()).newPage();
    await signIn(admin, ADMIN_EMAIL, "Admin");
    await admin.goto("/moderation");
    const item = admin.locator("li").filter({ hasText: newValue }).first();
    await expect(item).toBeVisible();
    await item.getByRole("button", { name: "Accept" }).click();
    await expect(admin.locator("li").filter({ hasText: newValue })).toHaveCount(0);

    // The published guide now shows the community-reported value.
    await contributor.goto(`/d/${GUIDE_SLUG}/explore?page=0`);
    await expect(contributor.getByText(newValue).first()).toBeVisible();
    await expect(contributor.locator("article").filter({ hasText: newValue }).getByText("Local contribution").first()).toBeVisible();

    // The contributor sees the decision in their history.
    await contributor.goto("/account");
    await expect(contributor.getByText("accepted").first()).toBeVisible();
  });

  test("signed-out readers are asked to sign in before contributing", async ({ page }) => {
    await page.goto(`/d/${GUIDE_SLUG}/eat`);
    await page.getByRole("button", { name: /Still true\?/ }).first().click();
    await expect(page.getByRole("link", { name: "Sign in to contribute" })).toBeVisible();
  });
});
