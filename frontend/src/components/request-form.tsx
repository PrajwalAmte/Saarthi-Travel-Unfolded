"use client";

import { useRouter } from "next/navigation";
import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import { fieldErrors, requestSchema } from "@/lib/schemas";
import type { RequestView } from "@/lib/types";
import { Button } from "./ui/button";
import { Field, Input } from "./ui/field";
import { Notice } from "./ui/notice";

/** Request a missing destination. No account needed; duplicates are merged as demand. */
export function RequestForm({ initialQuery = "", emailAvailable = false }: { initialQuery?: string; emailAvailable?: boolean }) {
  const router = useRouter();
  const [errors, setErrors] = React.useState<Record<string, string>>({});
  const [error, setError] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const parsed = requestSchema.safeParse({
      query: f.get("query") ?? "",
      state: f.get("state") ?? "",
      email: emailAvailable ? (f.get("email") ?? "") : "",
    });
    setErrors({});
    setError(null);
    if (!parsed.success) {
      setErrors(fieldErrors(parsed.error));
      return;
    }
    setBusy(true);
    try {
      const r = await api<RequestView>("/api/requests", { method: "POST", body: parsed.data });
      router.push(`/requests/${r.trackingCode}${parsed.data.email ? "?emailed=1" : ""}`);
    } catch (err) {
      setError(problemMessage(err));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4 rounded-lg border border-line bg-surface p-5" noValidate>
      <Field label="Place name" htmlFor="rq-query" required error={errors.query} hint="A city, town, village, region or tourist destination in India">
        <Input id="rq-query" name="query" defaultValue={initialQuery} maxLength={120} aria-invalid={Boolean(errors.query)} />
      </Field>
      <Field label="State (optional)" htmlFor="rq-state" error={errors.state} hint="Helps when several places share a name">
        <Input id="rq-state" name="state" maxLength={60} />
      </Field>
      {emailAvailable ? (
        <Field
          label="Email me when it is ready (optional)"
          htmlFor="rq-email"
          error={errors.email}
          hint="We send one email when the guide is ready, then delete your address. Nothing else, ever."
        >
          <Input id="rq-email" name="email" type="email" autoComplete="email" maxLength={254} aria-invalid={Boolean(errors.email)} />
        </Field>
      ) : null}
      {error ? (
        <Notice tone="error" role="alert">
          {error}
        </Notice>
      ) : null}
      <Button type="submit" disabled={busy}>
        {busy ? "Sending…" : "Request this destination"}
      </Button>
      <p className="text-xs text-muted">No account needed. After sending, you will see how long it should take and get a link you can bookmark.</p>
    </form>
  );
}
