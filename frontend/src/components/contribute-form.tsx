"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import * as React from "react";
import { api, ClientApiError, problemMessage } from "@/lib/client-api";
import { fieldErrors, newPlaceSchema, newTipSchema } from "@/lib/schemas";
import { CoiFields } from "./guide/still-true";
import { LicenceGate } from "./guide/licence-gate";
import { useSession } from "./session-provider";
import { Button } from "./ui/button";
import { Field, Input, Select, Textarea } from "./ui/field";
import { Notice } from "./ui/notice";

type Kind = "place" | "tip";

export function ContributeForm({ destinationId, slug, name }: { destinationId: string; slug: string; name: string }) {
  const { session, loading } = useSession();
  const pathname = usePathname();
  const [kind, setKind] = React.useState<Kind>("place");
  const [errors, setErrors] = React.useState<Record<string, string>>({});
  const [result, setResult] = React.useState<{ tone: "success" | "error"; text: string } | null>(null);
  const [duplicates, setDuplicates] = React.useState<{ id: string; title: string; section: string }[]>([]);
  const [busy, setBusy] = React.useState(false);
  const formRef = React.useRef<HTMLFormElement>(null);

  if (loading) return <div className="skeleton h-64 w-full" />;
  if (!session?.authenticated) {
    return (
      <Notice tone="info" title="Sign in to contribute">
        Contributions need a free contributor account.{" "}
        <Link className="underline" href={`/signin?next=${encodeURIComponent(pathname)}`}>
          Sign in
        </Link>
      </Notice>
    );
  }

  async function submit(acknowledge: boolean) {
    const form = formRef.current;
    if (!form) return;
    const f = new FormData(form);
    const str = (k: string) => String(f.get(k) ?? "");
    setErrors({});
    setResult(null);
    let body: Record<string, unknown>;
    if (kind === "place") {
      const p = newPlaceSchema.safeParse({
        section: str("section"),
        name: str("name"),
        description: str("description"),
        address: str("address"),
        price: str("price"),
        hours: str("hours"),
        whatToOrder: str("whatToOrder"),
        website: str("website"),
        note: str("note"),
        evidenceUrl: str("evidenceUrl"),
        conflictOfInterest: f.get("coi") === "on",
        coiDetails: str("coiDetails"),
      });
      if (!p.success) return setErrors(fieldErrors(p.error));
      const { note, evidenceUrl, conflictOfInterest, coiDetails, ...payload } = p.data;
      body = { type: "new_place", destinationId, payload, note, evidenceUrl, conflictOfInterest, coiDetails, acknowledgePossibleDuplicate: acknowledge };
    } else {
      const p = newTipSchema.safeParse({ section: str("tipSection"), heading: str("heading"), text: str("text"), note: str("note"), evidenceUrl: str("evidenceUrl") });
      if (!p.success) return setErrors(fieldErrors(p.error));
      const { note, evidenceUrl, ...payload } = p.data;
      body = { type: "new_tip", destinationId, payload, note, evidenceUrl };
    }
    setBusy(true);
    try {
      const r = await api<{ message: string }>("/api/contributions", { method: "POST", body });
      setDuplicates([]);
      setResult({ tone: "success", text: r.message });
      form.reset();
    } catch (e) {
      if (e instanceof ClientApiError && e.problem.code === "possible_duplicate" && e.problem.candidates) {
        setDuplicates(e.problem.candidates);
      } else {
        setResult({ tone: "error", text: problemMessage(e) });
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <LicenceGate>
      <form
        ref={formRef}
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void submit(false);
        }}
        className="space-y-5 rounded-lg border border-line bg-surface p-5"
      >
        <fieldset className="flex flex-wrap gap-4">
          <legend className="mb-2 text-sm font-medium">What would you like to add?</legend>
          <label className="flex items-center gap-2 text-sm">
            <input type="radio" name="kind" checked={kind === "place"} onChange={() => setKind("place")} /> A place (to eat, stay, see or shop)
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="radio" name="kind" checked={kind === "tip"} onChange={() => setKind("tip")} /> A local tip
          </label>
        </fieldset>

        {kind === "place" ? (
          <>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Type" htmlFor="c-section">
                <Select id="c-section" name="section" defaultValue="eat">
                  <option value="eat">Place to eat</option>
                  <option value="drink">Café or bar</option>
                  <option value="stay">Place to stay</option>
                  <option value="see">Sight</option>
                  <option value="do">Activity</option>
                  <option value="buy">Shop or market</option>
                </Select>
              </Field>
              <Field label="Name" htmlFor="c-name" required error={errors.name}>
                <Input id="c-name" name="name" maxLength={120} aria-invalid={Boolean(errors.name)} />
              </Field>
            </div>
            <Field label="Why is it worth including?" htmlFor="c-desc" required error={errors.description} hint={`What makes it good, from your own experience in ${name}.`}>
              <Textarea id="c-desc" name="description" maxLength={1500} aria-invalid={Boolean(errors.description)} />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="What to order (optional)" htmlFor="c-order" error={errors.whatToOrder}>
                <Input id="c-order" name="whatToOrder" maxLength={300} />
              </Field>
              <Field label="Price (optional)" htmlFor="c-price" error={errors.price} hint="For example ₹150 for a meal">
                <Input id="c-price" name="price" maxLength={200} />
              </Field>
              <Field label="Opening hours (optional)" htmlFor="c-hours" error={errors.hours}>
                <Input id="c-hours" name="hours" maxLength={200} />
              </Field>
              <Field label="Address (optional)" htmlFor="c-addr" error={errors.address}>
                <Input id="c-addr" name="address" maxLength={300} />
              </Field>
            </div>
            <Field label="Website (optional)" htmlFor="c-web" error={errors.website}>
              <Input id="c-web" name="website" type="url" placeholder="https://" />
            </Field>
            <CoiFields error={errors.coiDetails} />
          </>
        ) : (
          <>
            <Field label="Topic" htmlFor="c-tip-section">
              <Select id="c-tip-section" name="tipSection" defaultValue="tips">
                <option value="tips">General local tip</option>
                <option value="getting_around">Getting around</option>
                <option value="neighbourhood">A neighbourhood</option>
                <option value="eat">A local dish or speciality</option>
                <option value="stay">Where to stay</option>
                <option value="practical">When to visit</option>
              </Select>
            </Field>
            <Field label="Short title (optional)" htmlFor="c-heading" error={errors.heading}>
              <Input id="c-heading" name="heading" maxLength={120} />
            </Field>
            <Field label="Your tip" htmlFor="c-text" required error={errors.text}>
              <Textarea id="c-text" name="text" maxLength={1500} aria-invalid={Boolean(errors.text)} />
            </Field>
          </>
        )}

        <Field label="Supporting link (optional)" htmlFor="c-ev" error={errors.evidenceUrl}>
          <Input id="c-ev" name="evidenceUrl" type="url" placeholder="https://" />
        </Field>
        <Field label="Note for reviewers (optional)" htmlFor="c-note" error={errors.note}>
          <Textarea id="c-note" name="note" maxLength={1000} className="min-h-16" />
        </Field>

        {duplicates.length ? (
          <Notice tone="warning" title="Similar places are already in this guide">
            <ul className="mt-1 list-disc pl-5">
              {duplicates.map((d) => (
                <li key={d.id}>
                  {d.title} <span className="text-muted">({d.section})</span>
                </li>
              ))}
            </ul>
            <Button type="button" size="sm" variant="outline" className="mt-2" onClick={() => void submit(true)} disabled={busy}>
              Mine is a different place: submit anyway
            </Button>
          </Notice>
        ) : null}
        {result ? (
          <Notice tone={result.tone} role={result.tone === "error" ? "alert" : "status"}>
            {result.text}{" "}
            {result.tone === "success" ? (
              <Link className="underline" href={`/d/${slug}`}>
                Back to the guide
              </Link>
            ) : null}
          </Notice>
        ) : null}
        <Button type="submit" disabled={busy}>
          {busy ? "Sending…" : "Submit for review"}
        </Button>
      </form>
    </LicenceGate>
  );
}
