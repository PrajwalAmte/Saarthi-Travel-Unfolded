export const dynamic = "force-dynamic";

/** Liveness probe for the hosting platform: answers without calling the API, so a slow backend never restarts this service. */
export function GET() {
  return new Response("ok", { headers: { "cache-control": "no-store", "content-type": "text/plain" } });
}
