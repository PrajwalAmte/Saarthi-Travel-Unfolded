import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const dateFmt = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
const monthFmt = new Intl.DateTimeFormat("en-IN", { month: "short", year: "numeric", timeZone: "UTC" });

/** Formats an ISO date or timestamp for display; returns null for missing values. */
export function formatDate(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso.length === 10 ? `${iso}T00:00:00Z` : iso);
  return Number.isNaN(d.getTime()) ? null : dateFmt.format(d);
}

export function formatMonth(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso.length === 10 ? `${iso}T00:00:00Z` : iso);
  return Number.isNaN(d.getTime()) ? null : monthFmt.format(d);
}

export function formatInr(n: number | null | undefined): string {
  if (n === null || n === undefined) return "—";
  return `₹${n.toLocaleString("en-IN")}`;
}

export function kindLabel(kind: string): string {
  const map: Record<string, string> = {
    state: "State",
    union_territory: "Union territory",
    district: "District",
    region: "Region",
    city: "City",
    town: "Town",
    village: "Village",
    neighbourhood: "Neighbourhood",
    park: "Park or protected area",
    island: "Island",
    attraction: "Attraction",
    other: "Place",
  };
  return map[kind] ?? "Place";
}

/** Only allow http(s) links to be rendered as anchors. */
export function safeHref(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const u = new URL(url);
    return u.protocol === "http:" || u.protocol === "https:" ? u.toString() : null;
  } catch {
    return null;
  }
}

/** Google Maps link for a point (or a text search when no coordinates are known). */
export function googleMapsHref(lat: number | null | undefined, lon: number | null | undefined, query?: string): string | null {
  if (lat !== null && lat !== undefined && lon !== null && lon !== undefined) {
    return `https://www.google.com/maps/search/?api=1&query=${lat.toFixed(6)}%2C${lon.toFixed(6)}`;
  }
  return query ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}` : null;
}
