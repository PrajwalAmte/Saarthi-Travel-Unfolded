"use client";

import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import { useSession } from "../session-provider";
import { Button } from "../ui/button";
import { Notice } from "../ui/notice";

/** Asks the contributor to accept the contributor licence once, before their first contribution. */
export function LicenceGate({ children }: { children: React.ReactNode }) {
  const { session, refresh } = useSession();
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  if (session?.user?.licenceAccepted) {
    return <>{children}</>;
  }
  return (
    <div className="space-y-3 rounded-md border border-line bg-sunken/60 p-4 text-sm">
      <p className="font-medium text-ink">Contributor licence</p>
      <p className="text-ink-2">
        Contributions are published under{" "}
        <a className="underline" href="https://creativecommons.org/licenses/by-sa/4.0/" target="_blank" rel="noopener noreferrer">
          CC BY-SA 4.0
        </a>
        , the same licence as the Wikivoyage text they often correct, so others can reuse and share them with attribution.
        You can choose to be credited by display name or anonymously in your account settings.
      </p>
      {error ? <Notice tone="error">{error}</Notice> : null}
      <Button
        type="button"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          setError(null);
          try {
            await api("/api/me/licence", { method: "POST", body: { accept: true } });
            await refresh();
          } catch (e) {
            setError(problemMessage(e));
          } finally {
            setBusy(false);
          }
        }}
      >
        I agree to license my contributions under CC BY-SA 4.0
      </Button>
    </div>
  );
}
