import { BadgeCheck, Clock, Globe, Info, MapPin, Phone, Tag, Utensils } from "lucide-react";
import type { Entry, Fact } from "@/lib/types";
import { formatInr, formatMonth, safeHref } from "@/lib/utils";
import { Badge } from "../ui/badge";
import { FactMeta } from "./fact-meta";
import { ReportMenu } from "./report-menu";
import { StillTrue } from "./still-true";

const DETAIL_META: Record<string, { label: string; Icon: typeof Clock }> = {
  what_to_order: { label: "What to order", Icon: Utensils },
  price: { label: "Price", Icon: Tag },
  hours: { label: "Hours", Icon: Clock },
  address: { label: "Address", Icon: MapPin },
  phone: { label: "Phone", Icon: Phone },
  website: { label: "Website", Icon: Globe },
  accessibility: { label: "Accessibility", Icon: Info },
};

const LABEL_TONE: Record<string, "accent" | "verified" | "community" | "stale" | "neutral"> = {
  major: "accent",
  price_tier: "neutral",
  regional: "verified",
  popular: "neutral",
  accessibility: "neutral",
  local_contribution: "community",
  temporarily_closed: "stale",
};

/** Prices with a parsed rupee amount are shown as the amount; the source wording stays one hover away. */
function priceText(fact: Fact, description: string | null): string {
  const v = fact.value;
  if (fact.kind !== "price" || !v || typeof v.min !== "number" || typeof v.max !== "number") return fact.text;
  const amount = v.free === true && v.max === 0 ? "Free" : v.min === v.max ? formatInr(v.min) : `${formatInr(v.min)} – ${formatInr(v.max)}`;
  const raw = fact.text.trim();
  const inDescription = description ? description.includes(raw) : false;
  if (raw.replace(/\s/g, "") === amount.replace(/\s/g, "") || /^(₹|rs\.?|inr)\s?[\d,.\s–-]+$/i.test(raw)) return amount;
  return inDescription || raw.length > 80 ? `${amount} (see listing text)` : `${amount} · “${raw}”`;
}

/** Prices and opening hours change; when theirs is old, say so quietly next to the value. */
function freshnessHint(fact: Fact): string | null {
  if (fact.kind !== "price" && fact.kind !== "hours") return null;
  if (fact.verificationStatus !== "needs_reverification") return null;
  return fact.observedAt ? `as of ${formatMonth(fact.observedAt)}` : "may have changed";
}

function DetailRow({ fact, destinationId, description }: { fact: Fact; destinationId: string; description: string | null }) {
  const meta = DETAIL_META[fact.kind] ?? { label: fact.kind, Icon: Info };
  const href = fact.kind === "website" ? safeHref(fact.text) : null;
  const hint = freshnessHint(fact);
  const conflict = fact.verificationStatus === "conflicting_reports";
  const confirmed = fact.verificationStatus === "community_verified";
  return (
    <div className="grid grid-cols-[1.25rem_1fr] gap-x-2 py-1.5">
      <meta.Icon className="mt-0.5 size-4 text-muted" aria-hidden />
      <div className="min-w-0">
        <span className="sr-only">{meta.label}: </span>
        {href ? (
          <a href={href} target="_blank" rel="noopener noreferrer nofollow" className="break-all text-accent underline">
            {fact.text.replace(/^https?:\/\/(www\.)?/, "").replace(/\/$/, "")}
          </a>
        ) : (
          <span className="text-ink-2" title={fact.kind === "price" ? fact.text : undefined}>
            {priceText(fact, description)}
          </span>
        )}
        {confirmed ? (
          <span className="ml-2 inline-flex items-center gap-1 text-xs text-verified">
            <BadgeCheck className="size-3" aria-hidden />
            confirmed{fact.lastVerifiedAt ? ` ${formatMonth(fact.lastVerifiedAt)}` : ""}
          </span>
        ) : null}
        {conflict ? <span className="ml-2 text-xs text-conflict">reported as changed</span> : null}
        {hint && !conflict && !confirmed ? <span className="ml-2 text-xs text-muted">{hint}</span> : null}
        {fact.kind === "price" || fact.kind === "hours" ? (
          <span className="ml-2 text-xs">
            <StillTrue fact={fact} destinationId={destinationId} />
          </span>
        ) : null}
      </div>
    </div>
  );
}

/** One recommendation with its evidence, freshness, ranking explanation and actions. */
export function EntryCard({ entry, destinationId, destinationSlug, rank }: { entry: Entry; destinationId: string; destinationSlug: string; rank?: number }) {
  return (
    <article className="border-b border-line py-6 first:pt-2 last:border-b-0" aria-labelledby={`entry-${entry.id}`}>
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
        <div className="min-w-0">
          <h3 id={`entry-${entry.id}`} className="font-serif text-xl font-semibold leading-snug">
            {rank ? <span className="mr-2 text-base font-normal text-muted">{rank}.</span> : null}
            {entry.title}
          </h3>
          <p className="mt-0.5 text-sm text-muted">
            {[entry.altTitle, entry.area, entry.subsection].filter(Boolean).join(" · ")}
            {entry.distanceKm !== null ? `${entry.altTitle || entry.area || entry.subsection ? " · " : ""}${entry.distanceKm.toFixed(1)} km from centre` : ""}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2 text-sm">
          <a href={entry.mapUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-accent underline">
            <MapPin className="size-4" aria-hidden />
            {entry.latitude !== null ? "Map" : "Find on map"}
          </a>
          <ReportMenu entry={entry} destinationId={destinationId} destinationSlug={destinationSlug} />
        </div>
      </div>

      {entry.labels.length ? (
        <ul className="mt-2 flex flex-wrap gap-1.5" aria-label="Attributes">
          {entry.labels.map((l) => (
            <li key={l.code}>
              <Badge tone={LABEL_TONE[l.code] ?? "neutral"} title={l.basis ?? undefined}>
                {l.text}
              </Badge>
            </li>
          ))}
        </ul>
      ) : null}

      {entry.description ? (
        <div className="mt-3">
          <p className="whitespace-pre-line leading-relaxed text-ink-2">{entry.description.text}</p>
          <FactMeta fact={entry.description} destinationId={destinationId} />
        </div>
      ) : (
        null
      )}

      {entry.details.length ? (
        <div className="mt-3 rounded-md bg-sunken/70 px-3 py-1.5 text-sm">
          {entry.details.map((f) => (
            <DetailRow key={f.id} fact={f} destinationId={destinationId} description={entry.description?.text ?? null} />
          ))}
        </div>
      ) : null}

      {entry.branches?.length ? (
        <section className="mt-4" aria-label={`Other locations of ${entry.title}`}>
          <h4 className="text-sm font-medium text-ink">
            Also at {entry.branches.length} other location{entry.branches.length === 1 ? "" : "s"}
          </h4>
          <ul className="mt-2 divide-y divide-line rounded-md border border-line text-sm">
            {entry.branches.map((b) => (
              <li key={b.id} className="px-3 py-2">
                <div className="flex flex-wrap items-center justify-between gap-x-3">
                  <span className="font-medium text-ink-2">{[b.area, b.subsection].filter(Boolean).join(" · ") || "Another branch"}</span>
                  <a href={b.mapUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-accent underline">
                    <MapPin className="size-4" aria-hidden />
                    {b.latitude !== null ? "Map" : "Find on map"}
                  </a>
                </div>
                {b.note ? <p className="mt-1 text-ink-2">{b.note.text}</p> : null}
                {b.details.map((f) => (
                  <DetailRow key={f.id} fact={f} destinationId={destinationId} description={b.note?.text ?? entry.description?.text ?? null} />
                ))}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {entry.reasons.length ? (
        <details className="group mt-3 text-sm">
          <summary className="cursor-pointer select-none text-muted hover:text-ink">Why it&apos;s listed</summary>
          <ul className="mt-2 list-disc space-y-1 pl-5 text-ink-2">
            {entry.reasons.map((r, i) => (
              <li key={`${r.code}-${i}`}>{r.text}</li>
            ))}
          </ul>
        </details>
      ) : null}
    </article>
  );
}
