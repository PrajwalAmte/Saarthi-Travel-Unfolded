import "server-only";
import type { ApiProblem } from "./types";

/** Base URL of the Spring Boot API as seen from the Next.js server. */
export function backendUrl(): string {
  return (process.env.BACKEND_INTERNAL_URL ?? "http://localhost:8080").replace(/\/+$/, "");
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly problem: ApiProblem | null,
  ) {
    super(problem?.detail ?? `API request failed with status ${status}`);
  }
}

/** Statuses that mean "the service is busy or starting", not "this request is wrong". */
const TRANSIENT = new Set([502, 503, 504]);

async function fetchOnce<T>(path: string, timeoutMs: number): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(`${backendUrl()}${path}`, {
      cache: "no-store",
      headers: { Accept: "application/json" },
      signal: controller.signal,
    });
    if (!res.ok) {
      let problem: ApiProblem | null = null;
      try {
        problem = (await res.json()) as ApiProblem;
      } catch {
        problem = null;
      }
      throw new ApiError(res.status, problem);
    }
    return (await res.json()) as T;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Server-side GET against the API. Guides are always read fresh: the API caches safe public
 * responses itself and invalidates them after accepted changes. A slow first load (the service
 * waking up, or busy) gets a generous timeout and one retry on a timeout, a network error or a
 * 502/503/504, so a single slow call no longer turns into an error page. Real errors (404, 400…)
 * are not retried.
 */
export async function apiGet<T>(path: string, init?: { timeoutMs?: number }): Promise<T> {
  const timeoutMs = init?.timeoutMs ?? 25_000;
  try {
    return await fetchOnce<T>(path, timeoutMs);
  } catch (e) {
    const transient = e instanceof ApiError ? TRANSIENT.has(e.status) : true;
    if (!transient) throw e;
    return fetchOnce<T>(path, timeoutMs);
  }
}

/** Like apiGet but returns null for 404 instead of throwing. */
export async function apiGetOrNull<T>(path: string): Promise<T | null> {
  try {
    return await apiGet<T>(path);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      return null;
    }
    throw e;
  }
}
