import "server-only";
import { apiGet } from "./server-api";
import { MIN_TRIPS } from "./trips";
import type { WeekendTrips } from "./types";

export type TripOrigin = { slug: string; name: string; radiusKm: number };

/** Trip origins that have enough guided places around them for a weekend-trips page, with their trip counts. */
export async function tripOrigins(): Promise<(TripOrigin & { total: number; maxKm: number })[]> {
  try {
    const origins = await apiGet<TripOrigin[]>("/api/trips/origins", { timeoutMs: 8000 });
    const pages = await Promise.all(
      origins.map((o) =>
        apiGet<WeekendTrips>(`/api/trips/weekend-from/${o.slug}`, { timeoutMs: 8000 })
          .then((w) => ({ ...o, total: w.total, maxKm: w.maxKm }))
          .catch(() => null),
      ),
    );
    return pages.filter((p): p is TripOrigin & { total: number; maxKm: number } => p !== null && p.total >= MIN_TRIPS);
  } catch {
    return [];
  }
}

/** Number of weekend trips from this place, or null when it has no weekend-trips page. */
export async function weekendTripCount(slug: string): Promise<number | null> {
  return (await tripOrigins()).find((o) => o.slug === slug)?.total ?? null;
}
