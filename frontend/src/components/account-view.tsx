"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import * as React from "react";
import { api, problemMessage } from "@/lib/client-api";
import type { HistoryItem, Page, Profile } from "@/lib/types";
import { formatDate } from "@/lib/utils";
import { useSession } from "./session-provider";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";
import { Dialog, DialogContent, DialogTrigger } from "./ui/dialog";
import { Field, Input, Select } from "./ui/field";
import { Notice } from "./ui/notice";

const TYPE_LABEL: Record<string, string> = {
  correction: "Correction",
  closure_report: "Closure report",
  duplicate_report: "Duplicate report",
  incorrect_report: "Incorrect information",
  new_place: "New place",
  new_tip: "Local tip",
};

const STATUS_TONE: Record<string, "neutral" | "verified" | "stale" | "conflict"> = {
  pending: "stale",
  accepted: "verified",
  rejected: "conflict",
  reverted: "conflict",
  withdrawn: "neutral",
};

const TIER_LABEL: Record<string, string> = { contributor: "Contributor", trusted: "Trusted contributor", steward: "City steward" };

export function AccountView() {
  const { session, loading, refresh } = useSession();
  const router = useRouter();
  const [profile, setProfile] = React.useState<Profile | null>(null);
  const [history, setHistory] = React.useState<Page<HistoryItem> | null>(null);
  const [page, setPage] = React.useState(0);
  const [error, setError] = React.useState<string | null>(null);
  const [saved, setSaved] = React.useState<string | null>(null);

  const [version, setVersion] = React.useState(0);
  const reload = () => setVersion((v) => v + 1);

  React.useEffect(() => {
    if (!session?.authenticated) return;
    let cancelled = false;
    Promise.all([api<Profile>("/api/me"), api<Page<HistoryItem>>(`/api/me/contributions?page=${page}`)])
      .then(([p, h]) => {
        if (cancelled) return;
        setProfile(p);
        setHistory(h);
      })
      .catch((e) => {
        if (!cancelled) setError(problemMessage(e));
      });
    return () => {
      cancelled = true;
    };
  }, [session?.authenticated, page, version]);

  if (loading) return <div className="skeleton h-64 w-full" />;
  if (!session?.authenticated) {
    return (
      <div className="max-w-xl space-y-4">
        <h1 className="text-3xl font-semibold">Your contributions</h1>
        <p className="text-ink-2">Sign in to see your contributions, verifications and trust level.</p>
        <Button asChild>
          <Link href="/signin?next=/account">Sign in</Link>
        </Button>
      </div>
    );
  }
  if (error) return <Notice tone="error">{error}</Notice>;
  if (!profile || !history) return <div className="skeleton h-64 w-full" />;

  const accepted = profile.contributions.accepted ?? 0;

  async function saveProfile(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setSaved(null);
    try {
      const name = String(f.get("displayName") ?? "").trim();
      if (name.length < 1 || name.length > 60) {
        setSaved("Display name must be 1 to 60 characters.");
        return;
      }
      setProfile(await api<Profile>("/api/me", { method: "PATCH", body: { displayName: name, attribution: f.get("attribution") } }));
      await refresh();
      setSaved("Saved.");
    } catch (err) {
      setSaved(problemMessage(err));
    }
  }

  return (
    <div className="grid gap-10 lg:grid-cols-[20rem_minmax(0,1fr)]">
      <aside className="space-y-6">
        <div>
          <h1 className="text-3xl font-semibold">{profile.displayName}</h1>
          <p className="mt-1 text-sm text-muted">
            {TIER_LABEL[profile.tier]}
            {profile.admin ? " · Administrator" : ""} · joined {formatDate(profile.createdAt)}
          </p>
          {profile.stewardOf.length ? (
            <p className="mt-1 text-sm text-ink-2">
              Steward for {profile.stewardOf.map((p, i) => (
                <span key={p.slug}>
                  <Link className="underline" href={`/d/${p.slug}`}>{p.name}</Link>
                  {i < profile.stewardOf.length - 1 ? ", " : ""}
                </span>
              ))}
            </p>
          ) : null}
        </div>
        <dl className="grid grid-cols-2 gap-3 text-sm">
          <div className="rounded-md border border-line bg-surface p-3">
            <dt className="text-muted">Accepted contributions</dt>
            <dd className="text-xl font-semibold">{accepted}</dd>
          </div>
          <div className="rounded-md border border-line bg-surface p-3">
            <dt className="text-muted">Pending</dt>
            <dd className="text-xl font-semibold">{profile.contributions.pending ?? 0}</dd>
          </div>
          <div className="rounded-md border border-line bg-surface p-3">
            <dt className="text-muted">Verifications counted</dt>
            <dd className="text-xl font-semibold">{profile.verificationsAccepted}</dd>
          </div>
          <div className="rounded-md border border-line bg-surface p-3">
            <dt className="text-muted">Verifications pending</dt>
            <dd className="text-xl font-semibold">{profile.verificationsPending}</dd>
          </div>
        </dl>
        {profile.nextTier && !profile.admin ? (
          <Notice tone="info" title="Becoming a trusted contributor">
            Trusted contributors publish edits directly and can review others’ submissions. It happens automatically after{" "}
            {profile.nextTier.minAccepted} accepted contributions or verifications with at most{" "}
            {Math.round(profile.nextTier.maxRejectionRatio * 100)}% rejected, on an account at least {profile.nextTier.minAccountAgeDays} days
            old. You have {profile.trust.accepted} accepted.
          </Notice>
        ) : null}
        <form onSubmit={saveProfile} className="space-y-3 rounded-md border border-line bg-surface p-4">
          <Field label="Display name" htmlFor="acc-name">
            <Input id="acc-name" name="displayName" defaultValue={profile.displayName} maxLength={60} />
          </Field>
          <Field label="Credit my contributions as" htmlFor="acc-attr">
            <Select id="acc-attr" name="attribution" defaultValue={profile.attribution}>
              <option value="display_name">My display name</option>
              <option value="anonymous">Anonymous contributor</option>
            </Select>
          </Field>
          <Button type="submit" size="sm">
            Save
          </Button>
          {saved ? <p className="text-xs text-muted" role="status">{saved}</p> : null}
        </form>
        <p className="text-xs text-muted">
          Contributor licence:{" "}
          {profile.contributorLicenceAcceptedAt ? `${profile.contributorLicence} accepted ${formatDate(profile.contributorLicenceAcceptedAt)}` : "not yet accepted (asked before your first contribution)"}
          . {profile.email ? `Signed in as ${profile.email}.` : ""}
        </p>
        <DeleteAccount onDeleted={async () => { await refresh(); router.push("/"); }} />
      </aside>

      <section>
        <h2 className="text-2xl font-semibold">Contribution history</h2>
        {history.items.length === 0 ? (
          <p className="mt-3 text-ink-2">
            No contributions yet. Open any guide and use “Still true?” on a fact you know, or{" "}
            <Link className="text-accent underline" href="/search">
              find a destination
            </Link>
            .
          </p>
        ) : (
          <ul className="mt-4 divide-y divide-line rounded-lg border border-line bg-surface">
            {history.items.map((h) => (
              <li key={h.id} className="flex flex-col gap-2 p-4 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <p className="font-medium">
                    {TYPE_LABEL[h.type] ?? h.type}
                    {h.targetTitle ? <span className="font-normal text-ink-2"> · {h.targetTitle}</span> : null}
                    {typeof h.payload.name === "string" ? <span className="font-normal text-ink-2"> · {h.payload.name}</span> : null}
                  </p>
                  <p className="text-sm text-muted">
                    <Link className="underline" href={`/d/${h.destinationSlug}`}>{h.destinationName}</Link> · submitted {formatDate(h.createdAt)}
                    {h.decidedAt ? ` · decided ${formatDate(h.decidedAt)}` : ""}
                  </p>
                  {h.decisionReason ? <p className="mt-1 text-sm text-ink-2">{h.decisionReason}</p> : null}
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <Badge tone={STATUS_TONE[h.status] ?? "neutral"}>{h.status}</Badge>
                  {h.status === "pending" ? (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={async () => {
                        await api(`/api/me/contributions/${h.id}`, { method: "DELETE" });
                        reload();
                      }}
                    >
                      Withdraw
                    </Button>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        )}
        {history.totalPages > 1 ? (
          <div className="mt-4 flex items-center justify-between text-sm">
            <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              Previous
            </Button>
            <span className="text-muted">
              Page {page + 1} of {history.totalPages}
            </span>
            <Button variant="ghost" size="sm" disabled={page + 1 >= history.totalPages} onClick={() => setPage((p) => p + 1)}>
              Next
            </Button>
          </div>
        ) : null}
      </section>
    </div>
  );
}

function DeleteAccount({ onDeleted }: { onDeleted: () => Promise<void> }) {
  const [confirm, setConfirm] = React.useState("");
  const [error, setError] = React.useState<string | null>(null);
  return (
    <Dialog>
      <DialogTrigger asChild>
        <Button variant="link" className="text-conflict">
          Delete my account
        </Button>
      </DialogTrigger>
      <DialogContent title="Delete your account?" description="This cannot be undone.">
        <div className="space-y-3 text-sm text-ink-2">
          <p>
            Your email, display name and sign-in identities are removed and you are signed out. Pending contributions are
            withdrawn. Published contributions stay in the guide (they are licensed under CC BY-SA 4.0) but are attributed to
            an anonymous former contributor.
          </p>
          <Field label='Type "delete" to confirm' htmlFor="del-confirm">
            <Input id="del-confirm" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          </Field>
          {error ? <Notice tone="error">{error}</Notice> : null}
          <Button
            variant="danger"
            disabled={confirm.trim().toLowerCase() !== "delete"}
            onClick={async () => {
              try {
                await api("/api/me", { method: "DELETE" });
                await onDeleted();
              } catch (e) {
                setError(problemMessage(e));
              }
            }}
          >
            Delete account
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
