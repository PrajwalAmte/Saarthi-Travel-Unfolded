"use client";

import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import { fieldErrors, notifySchema } from "@/lib/schemas";
import { Button } from "./ui/button";
import { Field, Input } from "./ui/field";
import { Notice } from "./ui/notice";

/** Opt-in "email me when it is ready" on a request's status page. The address is not kept after the one email. */
export function NotifyMe({ code, alreadySubscribed = false, noGuideYet = false }: { code: string; alreadySubscribed?: boolean; noGuideYet?: boolean }) {
  const [done, setDone] = React.useState(alreadySubscribed);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [fieldError, setFieldError] = React.useState<string | null>(null);

  if (done) {
    return (
      <Notice tone="success" className="mt-6" role="status" title="We will email you">
        You will get one email when the guide is ready. We delete your address as soon as it is sent.
      </Notice>
    );
  }

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const parsed = notifySchema.safeParse({ email: new FormData(e.currentTarget).get("email") ?? "" });
    setError(null);
    setFieldError(null);
    if (!parsed.success) {
      setFieldError(fieldErrors(parsed.error).email ?? "Enter a valid email address");
      return;
    }
    setBusy(true);
    try {
      await api(`/api/requests/${code}/notify`, { method: "POST", body: parsed.data });
      setDone(true);
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="mt-6 space-y-3 rounded-lg border border-line bg-surface p-4" noValidate>
      <Field
        label={noGuideYet ? "Email me if a guide is added" : "Email me when it is ready"}
        htmlFor="notify-email"
        error={fieldError ?? undefined}
        hint="One email, then we delete your address. Nothing else, ever."
      >
        <Input id="notify-email" name="email" type="email" autoComplete="email" maxLength={254} aria-invalid={Boolean(fieldError)} />
      </Field>
      {error ? (
        <Notice tone="error" role="alert">
          {error}
        </Notice>
      ) : null}
      <Button type="submit" disabled={busy}>
        {busy ? "Saving…" : "Email me"}
      </Button>
    </form>
  );
}
