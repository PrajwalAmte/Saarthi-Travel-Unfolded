"use client";

import { Mail } from "lucide-react";
import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import { emailSignInSchema, fieldErrors } from "@/lib/schemas";
import { useSession } from "./session-provider";
import { Button } from "./ui/button";
import { Field, Input } from "./ui/field";
import { Notice } from "./ui/notice";

const PROVIDER_LABEL: Record<string, string> = { google: "Continue with Google", github: "Continue with GitHub" };

export function SignInForm({ next, initialError }: { next: string; initialError: string | null }) {
  const { session, loading } = useSession();
  const [errors, setErrors] = React.useState<Record<string, string>>({});
  const [error, setError] = React.useState<string | null>(initialError);
  const [sent, setSent] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);

  React.useEffect(() => {
    try {
      sessionStorage.setItem("idi.next", next);
    } catch {
      // storage unavailable; the verify page falls back to the account page
    }
  }, [next]);

  if (loading) return <div className="skeleton h-40 w-full" />;
  if (session?.authenticated && session.user) {
    return (
      <Notice tone="success" title={`Signed in as ${session.user.displayName}`}>
        <a className="underline" href={next}>
          Continue
        </a>
      </Notice>
    );
  }

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const parsed = emailSignInSchema.safeParse({ email: f.get("email") ?? "", displayName: f.get("displayName") ?? "" });
    setErrors({});
    setError(null);
    if (!parsed.success) return setErrors(fieldErrors(parsed.error));
    setBusy(true);
    try {
      const r = await api<{ message: string }>("/api/auth/email/start", { method: "POST", body: parsed.data });
      setSent(r.message);
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6">
      {error ? (
        <Notice tone="error" role="alert">
          {error}
        </Notice>
      ) : null}
      {session && !session.emailSignInAvailable && session.oauthProviders.length === 0 ? (
        <Notice tone="warning" title="Sign-in is not configured on this server">
          The operator has not configured email (SMTP) or an OAuth provider, so contributor accounts cannot be created here
          yet. Reading the guides works without an account.
        </Notice>
      ) : null}
      {session?.emailSignInAvailable ? (
        sent ? (
          <Notice tone="success" title="Check your email" role="status">
            {sent}
          </Notice>
        ) : (
          <form onSubmit={submit} className="space-y-4 rounded-lg border border-line bg-surface p-5" noValidate>
            <Field label="Email" htmlFor="si-email" required error={errors.email} hint="We email you a single-use sign-in link. No password.">
              <Input id="si-email" name="email" type="email" autoComplete="email" inputMode="email" aria-invalid={Boolean(errors.email)} />
            </Field>
            <Field label="Display name (for new accounts)" htmlFor="si-name" error={errors.displayName} hint="Shown next to your contributions unless you choose to be anonymous.">
              <Input id="si-name" name="displayName" autoComplete="nickname" maxLength={60} />
            </Field>
            <Button type="submit" disabled={busy}>
              <Mail /> {busy ? "Sending…" : "Email me a sign-in link"}
            </Button>
          </form>
        )
      ) : null}
      {session?.oauthProviders.length ? (
        <div className="space-y-2">
          {session.oauthProviders.map((p) => (
            <Button key={p} asChild variant="outline" className="w-full">
              <a href={`/oauth2/authorization/${p}`}>{PROVIDER_LABEL[p] ?? `Continue with ${p}`}</a>
            </Button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
