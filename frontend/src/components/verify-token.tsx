"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import { useSession } from "./session-provider";
import { Notice } from "./ui/notice";

/** Exchanges the token from the link fragment (never sent to servers in URLs) for a session. */
export function VerifyToken() {
  const router = useRouter();
  const { refresh } = useSession();
  const [error, setError] = React.useState<string | null>(null);
  const started = React.useRef(false);

  React.useEffect(() => {
    if (started.current) return;
    started.current = true;
    const token = new URLSearchParams(window.location.hash.slice(1)).get("token");
    window.history.replaceState(null, "", window.location.pathname);
    const verification = token
      ? api("/api/auth/email/verify", { method: "POST", body: { token } })
      : Promise.reject(new Error("This sign-in link is incomplete. Request a new one."));
    verification
      .then(() => refresh())
      .then(() => {
        let next = "/account";
        try {
          next = sessionStorage.getItem("idi.next") ?? "/account";
          sessionStorage.removeItem("idi.next");
        } catch {
          // storage unavailable
        }
        router.replace(next.startsWith("/") && !next.startsWith("//") ? next : "/account");
      })
      .catch((e: unknown) => setError(e instanceof Error && !("problem" in e) ? e.message : problemMessage(e)));
  }, [refresh, router]);

  if (error) {
    return (
      <Notice tone="error" title="Could not sign you in" role="alert">
        {error}{" "}
        <Link href="/signin" className="underline">
          Request a new link
        </Link>
      </Notice>
    );
  }
  return (
    <p className="text-ink-2" role="status">
      Checking your link…
    </p>
  );
}
