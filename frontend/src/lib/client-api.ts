"use client";

import type { ApiProblem } from "./types";

export class ClientApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly problem: ApiProblem,
  ) {
    super(problem.detail ?? `Request failed (${status})`);
  }
}

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie.split("; ").find((c) => c.startsWith(`${name}=`));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null;
}

let csrfPromise: Promise<void> | null = null;

/** Ensures the XSRF-TOKEN cookie exists by loading the session endpoint once. */
async function ensureCsrf(): Promise<void> {
  if (readCookie("XSRF-TOKEN")) return;
  if (!csrfPromise) {
    csrfPromise = fetch("/api/auth/session", { credentials: "same-origin" })
      .then(() => undefined)
      .finally(() => {
        csrfPromise = null;
      });
  }
  await csrfPromise;
}

/** Browser-side API call through the same-origin /api proxy, with CSRF protection. */
export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  try {
    return await request<T>(path, init);
  } catch (e) {
    // A missing or rotated CSRF cookie is refreshed once; a rejected request was not executed, so retrying is safe.
    if (e instanceof ClientApiError && e.problem.code === "invalid_csrf") {
      document.cookie = "XSRF-TOKEN=; Max-Age=0; path=/";
      return request<T>(path, init);
    }
    throw e;
  }
}

async function request<T>(path: string, init: { method?: string; body?: unknown }): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method !== "GET" && method !== "HEAD") {
    await ensureCsrf();
    const token = readCookie("XSRF-TOKEN");
    if (token) headers["X-XSRF-TOKEN"] = token;
  }
  if (init.body !== undefined) headers["Content-Type"] = "application/json";
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers,
      credentials: "same-origin",
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });
  } catch {
    throw new ClientApiError(0, { status: 0, code: "network", detail: "Could not reach the server. Check your connection." });
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  // Gateways and load balancers answer some failures with HTML; keep the status instead of throwing on parse.
  let data: unknown = null;
  let parsed = true;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      parsed = false;
    }
  }
  if (res.ok && !parsed) {
    throw new ClientApiError(res.status, { status: res.status, code: "invalid_response", detail: "The server sent an unexpected response. Please try again." });
  }
  if (!res.ok) {
    const problem = (data as ApiProblem | null) ?? { status: res.status };
    if (res.status === 401 && !problem.detail) problem.detail = "Please sign in to continue.";
    if (res.status === 403 && !problem.detail) problem.detail = "You do not have permission to do that.";
    if (res.status >= 500 && !problem.detail) problem.detail = "The guide service is waking up. Try again in a minute.";
    throw new ClientApiError(res.status, { ...problem, status: res.status });
  }
  return data as T;
}

export function problemMessage(e: unknown): string {
  if (e instanceof ClientApiError) {
    if (e.problem.errors?.length) {
      return e.problem.errors.map((er) => `${er.field.replace(/^payload\./, "")}: ${er.message}`).join("; ");
    }
    return e.problem.detail ?? e.message;
  }
  return "Something went wrong. Please try again.";
}
