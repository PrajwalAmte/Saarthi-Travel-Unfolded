"use client";

import { Flag } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import { closureSchema, fieldErrors, flagSchema, incorrectSchema } from "@/lib/schemas";
import type { Entry } from "@/lib/types";
import { useSession } from "../session-provider";
import { Button } from "../ui/button";
import { Dialog, DialogContent, DialogTrigger } from "../ui/dialog";
import { Field, Input, Select, Textarea } from "../ui/field";
import { Notice } from "../ui/notice";
import { LicenceGate } from "./licence-gate";

type Kind = "closure" | "duplicate" | "incorrect" | "abuse";

/** Report a closure, duplicate, incorrect information or abuse for one entry. */
export function ReportMenu({ entry, destinationId, destinationSlug }: { entry: Entry; destinationId: string; destinationSlug: string }) {
  const [open, setOpen] = React.useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <button type="button" className="inline-flex items-center gap-1 text-muted underline hover:text-ink">
          <Flag className="size-4" aria-hidden /> Report
        </button>
      </DialogTrigger>
      <DialogContent title={`Report a problem with ${entry.title}`} description="Reports are reviewed by a city steward before anything changes.">
        {open ? <ReportBody entry={entry} destinationId={destinationId} destinationSlug={destinationSlug} onDone={() => setOpen(false)} /> : null}
      </DialogContent>
    </Dialog>
  );
}

function ReportBody({ entry, destinationId, destinationSlug, onDone }: { entry: Entry; destinationId: string; destinationSlug: string; onDone: () => void }) {
  const { session, loading } = useSession();
  const pathname = usePathname();
  const router = useRouter();
  const [kind, setKind] = React.useState<Kind>("closure");
  const [errors, setErrors] = React.useState<Record<string, string>>({});
  const [result, setResult] = React.useState<{ tone: "success" | "error"; text: string } | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [dupQuery, setDupQuery] = React.useState("");
  const [dupResults, setDupResults] = React.useState<{ id: string; title: string; section: string }[]>([]);
  const [dupChoice, setDupChoice] = React.useState<string>("");

  React.useEffect(() => {
    if (kind !== "duplicate" || dupQuery.trim().length < 2) {
      return;
    }
    const t = setTimeout(async () => {
      try {
        const r = await api<{ id: string; title: string; section: string }[]>(
          `/api/destinations/${destinationSlug}/entries?q=${encodeURIComponent(dupQuery.trim())}`,
        );
        setDupResults(r.filter((x) => x.id !== entry.id));
      } catch {
        setDupResults([]);
      }
    }, 200);
    return () => clearTimeout(t);
  }, [kind, dupQuery, destinationSlug, entry.id]);

  if (loading) return <div className="skeleton h-24 w-full" />;
  if (!session?.authenticated) {
    return (
      <div className="space-y-3 text-sm">
        <p className="text-ink-2">Reports need a free contributor account so they can be reviewed and traced.</p>
        <Button asChild>
          <Link href={`/signin?next=${encodeURIComponent(pathname)}`}>Sign in to report</Link>
        </Button>
      </div>
    );
  }

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setErrors({});
    setResult(null);
    setBusy(true);
    try {
      let message = "";
      if (kind === "closure") {
        const p = closureSchema.safeParse({ closure: f.get("closure"), details: f.get("details") ?? "", evidenceUrl: f.get("evidenceUrl") ?? "" });
        if (!p.success) return setErrors(fieldErrors(p.error));
        message = (
          await api<{ message: string }>("/api/contributions", {
            method: "POST",
            body: { type: "closure_report", destinationId, targetEntryId: entry.id, payload: { closure: p.data.closure, details: p.data.details }, evidenceUrl: p.data.evidenceUrl },
          })
        ).message;
      } else if (kind === "duplicate") {
        if (!dupChoice) return setErrors({ duplicate: "Choose the entry this duplicates" });
        message = (
          await api<{ message: string }>("/api/contributions", {
            method: "POST",
            body: { type: "duplicate_report", destinationId, targetEntryId: entry.id, payload: { duplicateOfEntryId: dupChoice } },
          })
        ).message;
      } else if (kind === "incorrect") {
        const p = incorrectSchema.safeParse({ explanation: f.get("explanation") ?? "", evidenceUrl: f.get("evidenceUrl") ?? "" });
        if (!p.success) return setErrors(fieldErrors(p.error));
        message = (
          await api<{ message: string }>("/api/contributions", {
            method: "POST",
            body: { type: "incorrect_report", destinationId, targetEntryId: entry.id, payload: { explanation: p.data.explanation }, evidenceUrl: p.data.evidenceUrl },
          })
        ).message;
      } else {
        const p = flagSchema.safeParse({ reason: f.get("reason"), details: f.get("details") ?? "" });
        if (!p.success) return setErrors(fieldErrors(p.error));
        message = (await api<{ message: string }>("/api/flags", { method: "POST", body: { targetType: "entry", targetId: entry.id, ...p.data } })).message;
      }
      setResult({ tone: "success", text: message });
      router.refresh();
    } catch (err) {
      setResult({ tone: "error", text: problemMessage(err) });
    } finally {
      setBusy(false);
    }
  }

  if (result?.tone === "success") {
    return (
      <div className="space-y-4">
        <Notice tone="success" role="status">
          {result.text}
        </Notice>
        <Button variant="outline" onClick={onDone}>
          Close
        </Button>
      </div>
    );
  }

  const form = (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <Field label="What is the problem?" htmlFor="rp-kind">
        <Select id="rp-kind" value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
          <option value="closure">It has closed</option>
          <option value="duplicate">It is listed twice</option>
          <option value="incorrect">The information is wrong or misleading</option>
          <option value="abuse">Spam, self-promotion or offensive content</option>
        </Select>
      </Field>
      {kind === "closure" ? (
        <>
          <Field label="Closure" htmlFor="rp-closure">
            <Select id="rp-closure" name="closure" defaultValue="permanent">
              <option value="permanent">Closed permanently</option>
              <option value="temporary">Closed temporarily</option>
            </Select>
          </Field>
          <Field label="Details (optional)" htmlFor="rp-details" error={errors.details}>
            <Textarea id="rp-details" name="details" maxLength={500} />
          </Field>
          <Field label="Supporting link (optional)" htmlFor="rp-ev" error={errors.evidenceUrl}>
            <Input id="rp-ev" name="evidenceUrl" type="url" placeholder="https://" />
          </Field>
        </>
      ) : null}
      {kind === "duplicate" ? (
        <Field label="Which entry is it a duplicate of?" htmlFor="rp-dup" error={errors.duplicate} hint="Search this guide by name">
          <Input
            id="rp-dup"
            value={dupQuery}
            onChange={(e) => {
              setDupQuery(e.target.value);
              if (e.target.value.trim().length < 2) setDupResults([]);
            }}
            placeholder="Type at least 2 letters"
          />
          {dupResults.length ? (
            <ul className="mt-2 space-y-1">
              {dupResults.map((r) => (
                <li key={r.id}>
                  <label className="flex items-center gap-2 text-sm">
                    <input type="radio" name="dup" value={r.id} checked={dupChoice === r.id} onChange={() => setDupChoice(r.id)} />
                    {r.title} <span className="text-muted">({r.section})</span>
                  </label>
                </li>
              ))}
            </ul>
          ) : null}
        </Field>
      ) : null}
      {kind === "incorrect" ? (
        <>
          <Field label="What is wrong?" htmlFor="rp-expl" required error={errors.explanation}>
            <Textarea id="rp-expl" name="explanation" maxLength={1000} aria-invalid={Boolean(errors.explanation)} />
          </Field>
          <Field label="Supporting link (optional)" htmlFor="rp-ev2" error={errors.evidenceUrl}>
            <Input id="rp-ev2" name="evidenceUrl" type="url" placeholder="https://" />
          </Field>
        </>
      ) : null}
      {kind === "abuse" ? (
        <>
          <Field label="Reason" htmlFor="rp-reason">
            <Select id="rp-reason" name="reason" defaultValue="spam">
              <option value="spam">Spam</option>
              <option value="self_promotion">Undeclared self-promotion</option>
              <option value="offensive">Offensive</option>
              <option value="misleading">Deliberately misleading</option>
              <option value="other">Other</option>
            </Select>
          </Field>
          <Field label="Details (optional)" htmlFor="rp-adetails" error={errors.details}>
            <Textarea id="rp-adetails" name="details" maxLength={1000} />
          </Field>
        </>
      ) : null}
      {result?.tone === "error" ? <Notice tone="error" role="alert">{result.text}</Notice> : null}
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" disabled={busy}>
          {busy ? "Sending…" : "Send report"}
        </Button>
      </div>
    </form>
  );
  return kind === "abuse" ? form : <LicenceGate>{form}</LicenceGate>;
}
