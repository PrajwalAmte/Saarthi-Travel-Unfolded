import type { NextRequest } from "next/server";
import { proxy } from "@/lib/proxy";

export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ path: string[] }> };

async function handle(req: NextRequest, ctx: Ctx) {
  const { path } = await ctx.params;
  return proxy(req, "/api/" + path.map(encodeURIComponent).join("/"));
}

export { handle as GET, handle as POST, handle as PUT, handle as PATCH, handle as DELETE };
