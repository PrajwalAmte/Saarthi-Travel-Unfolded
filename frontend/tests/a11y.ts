import axe from "axe-core";

/** Runs axe-core against a rendered container and returns violation summaries. */
export async function axeViolations(container: Element): Promise<string[]> {
  const results = await axe.run(container, {
    rules: {
      // jsdom cannot compute layout or colours reliably.
      "color-contrast": { enabled: false },
      region: { enabled: false },
    },
  });
  return results.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length})`);
}
