import "server-only";
import { apiGet } from "./server-api";
import { MIN_TRIPS } from "./trips";

export type TripOrigin = {
  slug: string;
  name: string;
  radiusKm: number;
  minKm: number;
  stateSlug: string | null;
  stateName: string | null;
  total: number;
};

/** Trip origins with enough guided places around them for a weekend-trips page. */
export async function tripOrigins(): Promise<TripOrigin[]> {
  try {
    const origins = await apiGet<TripOrigin[]>("/api/trips/origins", { timeoutMs: 8000 });
    return origins.filter((o) => o.total >= MIN_TRIPS);
  } catch {
    return [];
  }
}

/** Number of weekend trips from this place, or null when it has no weekend-trips page. */
export async function weekendTripCount(slug: string): Promise<number | null> {
  return (await tripOrigins()).find((o) => o.slug === slug)?.total ?? null;
}
