import { expect, type Page } from "@playwright/test";

export const MAILPIT = process.env.E2E_MAILPIT_URL ?? "http://localhost:8025";
export const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? "admin@example.test";
export const GUIDE_SLUG = process.env.E2E_GUIDE_SLUG ?? "mysore";

/** Signs in through the real email-link flow, reading the link from Mailpit. */
export async function signIn(page: Page, email: string, displayName: string) {
  await page.goto("/signin");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel(/Display name/).fill(displayName);
  const before = Date.now();
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await expect(page.getByText("Check your email")).toBeVisible();
  const link = await latestLink(email, before);
  await page.goto(link);
  await page.waitForURL(/\/account/);
}

async function latestLink(email: string, after: number): Promise<string> {
  for (let i = 0; i < 30; i++) {
    const res = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}&limit=1`);
    const data = (await res.json()) as { messages: { ID: string; Created: string }[] };
    const msg = data.messages[0];
    if (msg && new Date(msg.Created).getTime() >= after - 2000) {
      const full = (await (await fetch(`${MAILPIT}/api/v1/message/${msg.ID}`)).json()) as { Text: string };
      const match = full.Text.match(/https?:\/\/\S+\/auth\/verify#token=[A-Za-z0-9_-]+/);
      if (match) return match[0].replace(/^https?:\/\/[^/]+/, "");
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`No sign-in email arrived for ${email}`);
}

export async function noHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
}
