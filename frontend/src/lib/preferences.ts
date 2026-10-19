import { z } from "zod";

export const INTERESTS = ["food", "culture", "nature", "adventure", "nightlife", "shopping", "family"] as const;

export const preferencesSchema = z.object({
  food: z.enum(["regional", "popular"]).optional(),
  budget: z.enum(["budget", "moderate", "premium"]).optional(),
  comfort: z.enum(["basic", "comfortable", "luxury"]).optional(),
  interests: z.array(z.enum(INTERESTS)).max(3).default([]),
});

export type Preferences = z.infer<typeof preferencesSchema>;

type Params = Record<string, string | string[] | undefined> | URLSearchParams;

function get(params: Params, key: string): string | undefined {
  if (params instanceof URLSearchParams) return params.get(key) ?? undefined;
  const v = params[key];
  return Array.isArray(v) ? v[0] : v;
}

/** Reads preferences from URL search params, dropping anything invalid. */
export function parsePreferences(params: Params): Preferences {
  const interests = (get(params, "interests") ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter((s): s is (typeof INTERESTS)[number] => (INTERESTS as readonly string[]).includes(s))
    .slice(0, 3);
  const raw = {
    food: get(params, "food") || undefined,
    budget: get(params, "budget") || undefined,
    comfort: get(params, "comfort") || undefined,
    interests,
  };
  const parsed = preferencesSchema.safeParse(raw);
  if (parsed.success) return parsed.data;
  return {
    interests,
    food: preferencesSchema.shape.food.safeParse(raw.food).data,
    budget: preferencesSchema.shape.budget.safeParse(raw.budget).data,
    comfort: preferencesSchema.shape.comfort.safeParse(raw.comfort).data,
  };
}

/** Serialises preferences to the query parameters the API and shareable URLs use. */
export function preferencesToParams(p: Preferences): URLSearchParams {
  const out = new URLSearchParams();
  if (p.food) out.set("food", p.food);
  if (p.budget) out.set("budget", p.budget);
  if (p.comfort) out.set("comfort", p.comfort);
  if (p.interests.length) out.set("interests", p.interests.join(","));
  return out;
}

export function hasPreferences(p: Preferences): boolean {
  return Boolean(p.food || p.budget || p.comfort || p.interests.length);
}
