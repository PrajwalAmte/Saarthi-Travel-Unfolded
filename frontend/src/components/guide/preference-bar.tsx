"use client";

import { SlidersHorizontal, X } from "lucide-react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import * as React from "react";
import { hasPreferences, parsePreferences, preferencesToParams, type Preferences } from "@/lib/preferences";
import { PreferencePicker } from "../preference-picker";
import { Button } from "../ui/button";

const LABELS: Record<string, string> = {
  regional: "Regional food",
  popular: "Widely popular",
  budget: "Budget-friendly",
  moderate: "Moderate",
  premium: "Premium",
  basic: "Basic stays",
  comfortable: "Comfortable stays",
  luxury: "Luxury stays",
  family: "Family-friendly",
};

function summary(p: Preferences): string[] {
  const out: string[] = [];
  if (p.food) out.push(LABELS[p.food] ?? p.food);
  if (p.budget) out.push(LABELS[p.budget] ?? p.budget);
  if (p.comfort) out.push(LABELS[p.comfort] ?? p.comfort);
  for (const i of p.interests) out.push(LABELS[i] ?? i[0]!.toUpperCase() + i.slice(1));
  return out;
}

/** Shows the active preferences and lets readers change them; changes go to the URL and the API. */
export function PreferenceBar() {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const current = parsePreferences(params);
  const [open, setOpen] = React.useState(false);
  const [draft, setDraft] = React.useState<Preferences>(current);

  function apply(p: Preferences) {
    const next = preferencesToParams(p);
    const category = params.get("category");
    if (category) next.set("category", category);
    router.replace(next.toString() ? `${pathname}?${next}` : pathname, { scroll: false });
    setOpen(false);
  }

  const items = summary(current);
  return (
    <div className="rounded-lg border border-line bg-surface">
      <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
        <p className="text-sm text-ink-2">
          {items.length ? (
            <>
              <span className="text-muted">Ranked for </span>
              {items.join(" · ")}
            </>
          ) : (
            <span className="text-muted">Tell us how you travel to see the best matches first.</span>
          )}
        </p>
        <div className="flex gap-2">
          {hasPreferences(current) ? (
            <Button variant="ghost" size="sm" onClick={() => apply({ interests: [] })}>
              <X /> Clear
            </Button>
          ) : null}
          <Button
            variant="outline"
            size="sm"
            aria-expanded={open}
            aria-controls="pref-panel"
            onClick={() => {
              setDraft(current);
              setOpen((o) => !o);
            }}
          >
            <SlidersHorizontal /> Preferences
          </Button>
        </div>
      </div>
      {open ? (
        <div id="pref-panel" className="border-t border-line px-4 py-4">
          <PreferencePicker value={draft} onChange={setDraft} />
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button size="sm" onClick={() => apply(draft)}>
              Apply
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
