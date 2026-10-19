"use client";

import { useRouter } from "next/navigation";
import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import { Button } from "./ui/button";
import { Notice } from "./ui/notice";

export function ChooseCandidate({
  code,
  candidates,
}: {
  code: string;
  candidates: { wikidataId: string; label: string | null; description: string | null }[];
}) {
  const router = useRouter();
  const [choice, setChoice] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  return (
    <form
      className="mt-6 space-y-3 rounded-lg border border-line bg-surface p-4"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!choice) return setError("Choose one of the places.");
        setBusy(true);
        setError(null);
        try {
          await api(`/api/requests/${code}/choose`, { method: "POST", body: { wikidataId: choice } });
          router.refresh();
        } catch (err) {
          setError(problemMessage(err));
        } finally {
          setBusy(false);
        }
      }}
    >
      <fieldset className="space-y-2">
        <legend className="font-medium">Which place do you mean?</legend>
        {candidates.map((c) => (
          <label key={c.wikidataId} className="flex items-start gap-2 text-sm">
            <input type="radio" name="candidate" value={c.wikidataId} checked={choice === c.wikidataId} onChange={() => setChoice(c.wikidataId)} className="mt-1" />
            <span>
              <span className="font-medium">{c.label ?? c.wikidataId}</span>
              {c.description ? <span className="text-muted"> — {c.description}</span> : null}
            </span>
          </label>
        ))}
      </fieldset>
      {error ? <Notice tone="error">{error}</Notice> : null}
      <Button type="submit" disabled={busy}>
        {busy ? "Sending…" : "Confirm"}
      </Button>
    </form>
  );
}
