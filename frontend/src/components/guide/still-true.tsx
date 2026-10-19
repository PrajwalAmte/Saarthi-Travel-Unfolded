"use client";

import { CheckCircle2, HelpCircle, PencilLine, XCircle } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import { correctionSchema, denySchema, fieldErrors } from "@/lib/schemas";
import type { Fact } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useSession } from "../session-provider";
import { Button } from "../ui/button";
import { Dialog, DialogContent, DialogTrigger } from "../ui/dialog";
import { Field, Input, Textarea } from "../ui/field";
import { Notice } from "../ui/notice";
import { LicenceGate } from "./licence-gate";

type Mode = "confirm" | "deny" | "changed";

/**
 * "Still true?": every answer creates a real verification record or a correction that goes
 * through moderation. Signed-out readers are asked to sign in; nothing is counted locally.
 */
export function StillTrue({ fact, destinationId }: { fact: Fact; destinationId: string }) {
  const [open, setOpen] = React.useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <button type="button" className="inline-flex items-center gap-1 font-medium text-accent underline hover:text-accent-strong">
          <HelpCircle className="size-3.5" aria-hidden /> Still true?
        </button>
      </DialogTrigger>
      <DialogContent title="Is this still true?" description="Your answer is recorded with your contributor account and shown on this fact.">
        <blockquote className="mb-4 max-h-40 overflow-y-auto whitespace-pre-line border-l-2 border-line-strong pl-3 text-sm text-ink-2">
          {fact.text}
        </blockquote>
        {open ? <StillTrueBody fact={fact} destinationId={destinationId} onDone={() => setOpen(false)} /> : null}
      </DialogContent>
    </Dialog>
  );
}

function StillTrueBody({ fact, destinationId, onDone }: { fact: Fact; destinationId: string; onDone: () => void }) {
  const { session, loading } = useSession();
  const router = useRouter();
  const pathname = usePathname();
  const [mode, setMode] = React.useState<Mode | null>(null);
  const [errors, setErrors] = React.useState<Record<string, string>>({});
  const [message, setMessage] = React.useState<{ tone: "success" | "error"; text: string } | null>(null);
  const [busy, setBusy] = React.useState(false);

  if (loading) {
    return <div className="skeleton h-24 w-full" aria-label="Loading" />;
  }
  if (!session?.authenticated) {
    return (
      <div className="space-y-3 text-sm">
        <p className="text-ink-2">
          Verifying needs a free contributor account so answers can be traced and rate-limited. Reading never needs one.
        </p>
        <Button asChild>
          <Link href={`/signin?next=${encodeURIComponent(pathname)}`}>Sign in to contribute</Link>
        </Button>
      </div>
    );
  }

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setErrors({});
    setMessage(null);
    setBusy(true);
    try {
      if (mode === "confirm") {
        const r = await api<{ message: string }>(`/api/facts/${fact.id}/verifications`, { method: "POST", body: { verdict: "confirm" } });
        setMessage({ tone: "success", text: r.message });
      } else if (mode === "deny") {
        const parsed = denySchema.safeParse({ note: form.get("note") ?? "", evidenceUrl: form.get("evidenceUrl") ?? "" });
        if (!parsed.success) {
          setErrors(fieldErrors(parsed.error));
          return;
        }
        const r = await api<{ message: string }>(`/api/facts/${fact.id}/verifications`, {
          method: "POST",
          body: { verdict: "deny", ...parsed.data },
        });
        setMessage({ tone: "success", text: r.message });
      } else if (mode === "changed") {
        const parsed = correctionSchema.safeParse({
          value: form.get("value") ?? "",
          note: form.get("note") ?? "",
          evidenceUrl: form.get("evidenceUrl") ?? "",
          conflictOfInterest: form.get("coi") === "on",
          coiDetails: form.get("coiDetails") ?? "",
        });
        if (!parsed.success) {
          setErrors(fieldErrors(parsed.error));
          return;
        }
        const { value, ...rest } = parsed.data;
        const r = await api<{ message: string }>("/api/contributions", {
          method: "POST",
          body: { type: "correction", destinationId, targetFactId: fact.id, payload: { value }, ...rest },
        });
        setMessage({ tone: "success", text: r.message });
      }
      router.refresh();
    } catch (err) {
      setMessage({ tone: "error", text: problemMessage(err) });
    } finally {
      setBusy(false);
    }
  }

  if (message?.tone === "success") {
    return (
      <div className="space-y-4">
        <Notice tone="success" role="status">
          {message.text}
        </Notice>
        <Button variant="outline" onClick={onDone}>
          Close
        </Button>
      </div>
    );
  }

  const options: { key: Mode; label: string; Icon: typeof CheckCircle2 }[] = [
    { key: "confirm", label: "Yes, still true", Icon: CheckCircle2 },
    { key: "deny", label: "No, not anymore", Icon: XCircle },
    { key: "changed", label: "It has changed", Icon: PencilLine },
  ];

  return (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <fieldset>
        <legend className="sr-only">Your answer</legend>
        <div className="grid gap-2 sm:grid-cols-3">
          {options.map(({ key, label, Icon }) => (
            <label
              key={key}
              className={cn(
                "flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2.5 text-sm has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-accent/40",
                mode === key ? "border-accent bg-accent-soft text-accent" : "border-line-strong hover:border-ink-2",
              )}
            >
              <input type="radio" name="mode" value={key} className="sr-only" checked={mode === key} onChange={() => setMode(key)} />
              <Icon className="size-4" aria-hidden />
              {label}
            </label>
          ))}
        </div>
      </fieldset>

      {mode === "deny" ? (
        <>
          <Field label="What is wrong? (optional)" htmlFor="st-note" error={errors.note}>
            <Textarea id="st-note" name="note" maxLength={500} placeholder="For example: it closed in 2025" />
          </Field>
          <Field label="Link that supports this (optional)" htmlFor="st-ev" error={errors.evidenceUrl}>
            <Input id="st-ev" name="evidenceUrl" type="url" inputMode="url" placeholder="https://" />
          </Field>
        </>
      ) : null}

      {mode === "changed" ? (
        <LicenceGate>
          <Field label="What is it now?" htmlFor="st-value" required error={errors.value} hint={fact.kind === "price" ? "Include the amount in rupees, for example ₹120 for a thali" : undefined}>
            <Textarea id="st-value" name="value" maxLength={1000} aria-invalid={Boolean(errors.value)} />
          </Field>
          <Field label="Link that supports this (optional)" htmlFor="st-ev2" error={errors.evidenceUrl}>
            <Input id="st-ev2" name="evidenceUrl" type="url" inputMode="url" placeholder="https://" />
          </Field>
          <Field label="Note for reviewers (optional)" htmlFor="st-note2" error={errors.note}>
            <Textarea id="st-note2" name="note" maxLength={1000} className="min-h-16" />
          </Field>
          <CoiFields error={errors.coiDetails} />
        </LicenceGate>
      ) : null}

      {message?.tone === "error" ? (
        <Notice tone="error" role="alert">
          {message.text}
        </Notice>
      ) : null}
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" disabled={!mode || busy}>
          {busy ? "Sending…" : "Submit"}
        </Button>
      </div>
    </form>
  );
}

export function CoiFields({ error }: { error?: string }) {
  const [coi, setCoi] = React.useState(false);
  return (
    <div className="space-y-2 rounded-md border border-line p-3">
      <label className="flex items-start gap-2 text-sm">
        <input type="checkbox" name="coi" className="mt-1" checked={coi} onChange={(e) => setCoi(e.target.checked)} />
        <span>I own, work for or am otherwise connected to this place.</span>
      </label>
      {coi ? (
        <Field label="Your connection" htmlFor="coi-details" required error={error} hint="Submissions from owners are always reviewed by a city steward and labelled.">
          <Input id="coi-details" name="coiDetails" maxLength={500} aria-invalid={Boolean(error)} />
        </Field>
      ) : null}
    </div>
  );
}
