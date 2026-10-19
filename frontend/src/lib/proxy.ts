import "server-only";
import type { NextRequest } from "next/server";
import { backendUrl } from "./server-api";

const FORWARD_REQUEST_HEADERS = [
  "accept",
  "accept-language",
  "content-type",
  "cookie",
  "user-agent",
  "x-xsrf-token",
  "x-request-id",
];

const FORWARD_RESPONSE_HEADERS = [
  "content-type",
  "cache-control",
  "location",
  "retry-after",
  "x-request-id",
];

/**
 * The client address to pass to the API for rate limiting. Only the hop added by a trusted load
 * balancer in front of this server can be believed: the client can send any X-Forwarded-For it
 * likes, and a load balancer appends the real address to the end. So when TRUST_PROXY_HEADERS is
 * set (deployed behind a platform proxy such as Render's), the right-most entry is forwarded alone;
 * otherwise nothing is forwarded and the API rate-limits by this server's own connection.
 */
export function trustedClientIp(req: { headers: Headers }, trust = process.env.TRUST_PROXY_HEADERS === "true"): string | null {
  if (!trust) return null;
  const chain = req.headers.get("x-forwarded-for");
  const last = chain?.split(",").map((s) => s.trim()).filter(Boolean).pop();
  return last && /^[0-9a-fA-F:.]{2,45}$/.test(last) ? last : null;
}

/**
 * Same-origin reverse proxy to the Spring Boot API. Keeps the session cookie first-party,
 * forwards the client address for rate limiting, and passes redirects through unchanged
 * (needed for OAuth sign-in).
 */
export async function proxy(req: NextRequest, path: string): Promise<Response> {
  const incoming = new URL(req.url);
  const target = `${backendUrl()}${path}${incoming.search}`;
  const headers = new Headers();
  for (const name of FORWARD_REQUEST_HEADERS) {
    const value = req.headers.get(name);
    if (value) headers.set(name, value);
  }
  const clientIp = trustedClientIp(req);
  if (clientIp) headers.set("x-forwarded-for", clientIp);
  headers.set("x-forwarded-proto", incoming.protocol.replace(":", ""));
  headers.set("x-forwarded-host", incoming.host);

  const method = req.method.toUpperCase();
  const body = method === "GET" || method === "HEAD" ? undefined : await req.arrayBuffer();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30_000);
  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method,
      headers,
      body,
      redirect: "manual",
      cache: "no-store",
      signal: controller.signal,
    });
  } catch {
    return Response.json(
      { status: 502, code: "upstream_unavailable", detail: "The API is not reachable right now. Please try again shortly." },
      { status: 502 },
    );
  } finally {
    clearTimeout(timer);
  }

  const out = new Headers();
  for (const name of FORWARD_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) out.set(name, value);
  }
  for (const cookie of upstream.headers.getSetCookie()) {
    out.append("set-cookie", cookie);
  }
  return new Response(upstream.status === 204 || upstream.status === 304 ? null : upstream.body, {
    status: upstream.status,
    headers: out,
  });
}
