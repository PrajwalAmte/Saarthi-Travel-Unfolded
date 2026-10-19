import "server-only";
import { cache } from "react";
import { apiGetOrNull } from "./server-api";
import type { DestinationDetail } from "./types";

/** Destination detail, de-duplicated across the layout and page of one request. */
export const getDestination = cache(async (slug: string): Promise<DestinationDetail | null> => {
  if (!/^[a-z0-9][a-z0-9-]{0,120}$/.test(slug)) return null;
  return apiGetOrNull<DestinationDetail>(`/api/destinations/${slug}`);
});
