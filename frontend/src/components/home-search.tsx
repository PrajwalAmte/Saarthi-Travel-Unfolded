"use client";

import { ArrowRight, SlidersHorizontal } from "lucide-react";
import { useRouter } from "next/navigation";
import * as React from "react";
import { hasPreferences, preferencesToParams, type Preferences } from "@/lib/preferences";
import type { SearchHit } from "@/lib/types";
import { PreferencePicker } from "./preference-picker";
import { destinationHref, SearchBox } from "./search-box";
import { Button } from "./ui/button";

export function HomeSearch() {
  const router = useRouter();
  const [prefs, setPrefs] = React.useState<Preferences>({ interests: [] });
  const [showPrefs, setShowPrefs] = React.useState(false);
  const [selected, setSelected] = React.useState<SearchHit | null>(null);
  const [query, setQuery] = React.useState("");
  const suffix = preferencesToParams(prefs).toString();

  const onQueryChange = React.useCallback((q: string) => {
    setQuery(q);
    setSelected((s) => (s && s.name === q ? s : null));
  }, []);

  function open() {
    if (selected) {
      router.push(destinationHref(selected, suffix));
    } else if (query.length >= 2) {
      router.push(`/search?q=${encodeURIComponent(query)}${suffix ? `&${suffix}` : ""}`);
    }
  }

  return (
    <div className="space-y-4">
      <SearchBox size="lg" suffix={suffix} onSelect={setSelected} onQueryChange={onQueryChange} onEnter={open} />
      <div className="flex flex-wrap items-center gap-3">
        <Button size="lg" onClick={open} disabled={query.length < 2}>
          {selected ? `Open the ${selected.name} guide` : "Find destination"}
          <ArrowRight />
        </Button>
        <Button
          variant="ghost"
          onClick={() => setShowPrefs((s) => !s)}
          aria-expanded={showPrefs}
          aria-controls="home-preferences"
        >
          <SlidersHorizontal />
          {hasPreferences(prefs) ? "Edit preferences" : "Add preferences (optional)"}
        </Button>
      </div>
      {showPrefs ? (
        <div id="home-preferences" className="rounded-lg border border-line bg-surface p-4 sm:p-5">
          <PreferencePicker value={prefs} onChange={setPrefs} />
          <p className="mt-4 text-xs text-muted">
            Preferences change how the guide ranks entries, using attributes recorded from sources (for example a listing
            filed under “Budget”). You can change them on the guide page at any time.
          </p>
        </div>
      ) : null}
    </div>
  );
}
