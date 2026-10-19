"use client";

import { Check } from "lucide-react";
import * as React from "react";
import { INTERESTS, type Preferences } from "@/lib/preferences";
import { cn } from "@/lib/utils";

const FOOD = [
  { value: "regional", label: "Regional food" },
  { value: "popular", label: "Widely popular" },
] as const;
const BUDGET = [
  { value: "budget", label: "Budget-friendly" },
  { value: "moderate", label: "Moderate" },
  { value: "premium", label: "Premium" },
] as const;
const COMFORT = [
  { value: "basic", label: "Basic" },
  { value: "comfortable", label: "Comfortable" },
  { value: "luxury", label: "Luxury" },
] as const;
const INTEREST_LABELS: Record<(typeof INTERESTS)[number], string> = {
  food: "Food",
  culture: "Culture",
  nature: "Nature",
  adventure: "Adventure",
  nightlife: "Nightlife",
  shopping: "Shopping",
  family: "Family-friendly",
};

function Chip({ pressed, onClick, children, disabled }: { pressed: boolean; onClick: () => void; children: React.ReactNode; disabled?: boolean }) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      disabled={disabled}
      onClick={onClick}
      className={cn(
        "inline-flex min-h-9 items-center gap-1.5 rounded-md border px-3 text-sm transition-colors",
        pressed ? "border-accent bg-accent-soft text-accent" : "border-line-strong bg-surface text-ink-2 hover:border-ink-2",
        disabled && "cursor-not-allowed opacity-50",
      )}
    >
      {pressed ? <Check className="size-3.5" aria-hidden /> : null}
      {children}
    </button>
  );
}

function Group({ label, children, hint }: { label: string; children: React.ReactNode; hint?: string }) {
  const id = React.useId();
  return (
    <div role="group" aria-labelledby={id} className="space-y-2">
      <p id={id} className="text-xs font-semibold uppercase tracking-wide text-muted">
        {label} {hint ? <span className="font-normal normal-case tracking-normal">· {hint}</span> : null}
      </p>
      <div className="flex flex-wrap gap-2">{children}</div>
    </div>
  );
}

/**
 * Optional, skippable travel preferences. They are sent to the API, which uses them to rank
 * entries; nothing here filters results in the browser.
 */
export function PreferencePicker({ value, onChange }: { value: Preferences; onChange: (p: Preferences) => void }) {
  function toggle<K extends "food" | "budget" | "comfort">(key: K, v: NonNullable<Preferences[K]>) {
    onChange({ ...value, [key]: value[key] === v ? undefined : v });
  }
  function toggleInterest(i: (typeof INTERESTS)[number]) {
    const has = value.interests.includes(i);
    onChange({ ...value, interests: has ? value.interests.filter((x) => x !== i) : [...value.interests, i].slice(0, 3) });
  }
  return (
    <div className="grid gap-5 sm:grid-cols-2">
      <Group label="Food">
        {FOOD.map((o) => (
          <Chip key={o.value} pressed={value.food === o.value} onClick={() => toggle("food", o.value)}>
            {o.label}
          </Chip>
        ))}
      </Group>
      <Group label="Budget">
        {BUDGET.map((o) => (
          <Chip key={o.value} pressed={value.budget === o.value} onClick={() => toggle("budget", o.value)}>
            {o.label}
          </Chip>
        ))}
      </Group>
      <Group label="Comfort" hint="for stays">
        {COMFORT.map((o) => (
          <Chip key={o.value} pressed={value.comfort === o.value} onClick={() => toggle("comfort", o.value)}>
            {o.label}
          </Chip>
        ))}
      </Group>
      <Group label="Interests" hint="up to 3">
        {INTERESTS.map((i) => (
          <Chip
            key={i}
            pressed={value.interests.includes(i)}
            disabled={!value.interests.includes(i) && value.interests.length >= 3}
            onClick={() => toggleInterest(i)}
          >
            {INTEREST_LABELS[i]}
          </Chip>
        ))}
      </Group>
    </div>
  );
}
