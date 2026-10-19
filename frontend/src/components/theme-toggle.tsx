"use client";

import { Monitor, Moon, Sun } from "lucide-react";
import * as React from "react";
import { THEME_STORAGE_KEY as STORAGE_KEY } from "@/lib/theme";
import { cn } from "@/lib/utils";

type Theme = "light" | "dark" | "system";

const ORDER: Theme[] = ["light", "dark", "system"];
const LABEL: Record<Theme, string> = { light: "Light theme", dark: "Dark theme", system: "Match system theme" };

function apply(theme: Theme) {
  const root = document.documentElement;
  if (theme === "system") delete root.dataset.theme;
  else root.dataset.theme = theme;
  try {
    if (theme === "system") localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // The choice still applies for this visit.
  }
}

const CHANGE_EVENT = "saarthi-theme-change";

function readTheme(): Theme {
  const t = document.documentElement.dataset.theme;
  return t === "light" || t === "dark" ? t : "system";
}

function subscribe(onChange: () => void) {
  window.addEventListener(CHANGE_EVENT, onChange);
  return () => window.removeEventListener(CHANGE_EVENT, onChange);
}

/** Cycles light → dark → system. The icon shows the current choice. */
export function ThemeToggle({ className }: { className?: string }) {
  // The theme lives on <html> (set before paint); the server cannot know it, so it renders "system".
  const theme = React.useSyncExternalStore(subscribe, readTheme, () => "system" as Theme);
  const next: Theme = ORDER[(ORDER.indexOf(theme) + 1) % ORDER.length] ?? "light";
  const Icon = theme === "light" ? Sun : theme === "dark" ? Moon : Monitor;
  return (
    <button
      type="button"
      onClick={() => {
        apply(next);
        window.dispatchEvent(new Event(CHANGE_EVENT));
      }}
      className={cn(
        "inline-flex size-10 items-center justify-center rounded-full text-ink-2 transition-colors hover:bg-sunken hover:text-ink",
        className,
      )}
      aria-label={`${LABEL[theme]}. Switch to ${LABEL[next].toLowerCase()}`}
      title={`${LABEL[theme]} — click for ${LABEL[next].toLowerCase()}`}
    >
      <Icon className="size-5" aria-hidden />
    </button>
  );
}
