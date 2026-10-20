"use client";

import { Loader2, MapPin, Search } from "lucide-react";
import { useRouter } from "next/navigation";
import * as React from "react";
import { cn, kindLabel } from "@/lib/utils";
import type { SearchHit, SearchResult } from "@/lib/types";
import { CoverageBadge } from "./coverage-badge";

type Props = {
  /** Query string (preferences) appended when opening a destination. */
  suffix?: string;
  initialQuery?: string;
  autoFocus?: boolean;
  size?: "lg" | "md";
  onSelect?: (hit: SearchHit) => void;
  onQueryChange?: (q: string) => void;
  /** Called on Enter when no suggestion is highlighted (used by the homepage to open the chosen guide). */
  onEnter?: () => void;
};

export function destinationHref(hit: SearchHit, suffix = ""): string {
  const base = hit.kind === "neighbourhood" && hit.parentCitySlug ? `/d/${hit.parentCitySlug}/neighbourhoods` : `/d/${hit.guideSlug}`;
  return suffix ? `${base}?${suffix}` : base;
}

/**
 * Destination search with keyboard-accessible autocomplete (WAI-ARIA combobox pattern).
 * Suggestions come from the real index; when nothing matches, the reader is offered a
 * destination request instead of invented results.
 */
export function SearchBox({ suffix = "", initialQuery = "", autoFocus, size = "lg", onSelect, onQueryChange, onEnter }: Props) {
  const router = useRouter();
  const id = React.useId();
  const listId = `${id}-list`;
  const [query, setQuery] = React.useState(initialQuery);
  const [results, setResults] = React.useState<SearchHit[]>([]);
  const [open, setOpen] = React.useState(false);
  const [active, setActive] = React.useState(-1);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const [searched, setSearched] = React.useState("");
  const abortRef = React.useRef<AbortController | null>(null);

  React.useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      return;
    }
    const timer = setTimeout(async () => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      setLoading(true);
      setError(null);
      try {
        const res = await fetch(`/api/search?q=${encodeURIComponent(q)}&limit=8`, { signal: controller.signal });
        if (!res.ok) {
          throw new Error(res.status === 429 ? "Too many searches. Wait a moment." : "The guide service is waking up. Try again in a minute.");
        }
        const data = (await res.json()) as SearchResult;
        setResults(data.results);
        setSearched(q);
        setActive(data.results.length ? 0 : -1);
      } catch (e) {
        if ((e as Error).name !== "AbortError") {
          setError((e as Error).message);
          setResults([]);
        }
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }, 180);
    return () => clearTimeout(timer);
  }, [query]);

  function onInput(value: string) {
    setQuery(value);
    setOpen(true);
    const q = value.trim();
    onQueryChange?.(q);
    if (q.length < 2) {
      abortRef.current?.abort();
      setLoading(false);
      setResults([]);
      setSearched("");
      setError(null);
    }
  }

  const options = results.length;
  const showRequest = !loading && !error && searched.length >= 2 && results.length === 0;

  function choose(hit: SearchHit) {
    setOpen(false);
    if (onSelect) {
      onSelect(hit);
      setQuery(hit.name);
      return;
    }
    router.push(destinationHref(hit, suffix));
  }

  function submit() {
    const q = query.trim();
    if (open && active >= 0 && results[active]) {
      choose(results[active]);
      return;
    }
    if (onEnter) {
      onEnter();
      return;
    }
    if (q.length >= 2) {
      router.push(`/search?q=${encodeURIComponent(q)}${suffix ? `&${suffix}` : ""}`);
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setOpen(true);
      setActive((a) => (options ? (a + 1) % options : -1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setOpen(true);
      setActive((a) => (options ? (a - 1 + options) % options : -1));
    } else if (e.key === "Enter") {
      e.preventDefault();
      submit();
    } else if (e.key === "Escape") {
      setOpen(false);
      setActive(-1);
    }
  }

  const expanded = open && (results.length > 0 || showRequest || Boolean(error));
  const activeId = expanded && active >= 0 ? `${id}-opt-${active}` : undefined;

  return (
    <div className="relative">
      <label htmlFor={`${id}-input`} className="sr-only">
        Search for a city, town, region or destination in India
      </label>
      <div
        className={cn(
          "flex items-center gap-2 rounded-lg border border-line-strong bg-surface shadow-sm focus-within:border-accent focus-within:ring-2 focus-within:ring-accent/25",
          size === "lg" ? "h-14 px-4" : "h-11 px-3",
        )}
      >
        {loading ? <Loader2 className="size-5 shrink-0 animate-spin text-muted" aria-hidden /> : <Search className="size-5 shrink-0 text-muted" aria-hidden />}
        <input
          id={`${id}-input`}
          role="combobox"
          aria-expanded={expanded}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={activeId}
          autoComplete="off"
          spellCheck={false}
          autoFocus={autoFocus}
          value={query}
          placeholder="Try Mysuru, Bangalore, Hampi or Coorg"
          onChange={(e) => onInput(e.target.value)}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 120)}
          onKeyDown={onKeyDown}
          className={cn("min-w-0 flex-1 bg-transparent text-ink placeholder:text-muted focus:outline-none", size === "lg" ? "text-lg" : "text-base")}
        />
      </div>
      <div aria-live="polite" className="sr-only">
        {searched && !loading ? `${results.length} destinations found` : ""}
      </div>
      {expanded ? (
        <ul
          id={listId}
          role="listbox"
          aria-label="Matching destinations"
          className="absolute left-0 right-0 top-full z-30 mt-2 max-h-[22rem] overflow-y-auto rounded-lg border border-line bg-surface p-1 shadow-xl"
        >
          {error ? (
            <li className="px-3 py-3 text-sm text-conflict" role="presentation">
              {error}
            </li>
          ) : null}
          {results.map((hit, i) => (
            <li
              key={hit.id}
              id={`${id}-opt-${i}`}
              role="option"
              aria-selected={i === active}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => choose(hit)}
              onMouseEnter={() => setActive(i)}
              className={cn("flex cursor-pointer items-start gap-3 rounded-md px-3 py-2.5", i === active && "bg-sunken")}
            >
              <MapPin className="mt-0.5 size-4 shrink-0 text-muted" aria-hidden />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-medium text-ink">{hit.name}</span>
                  {hit.matchedAlias ? <span className="text-xs text-muted">matched “{hit.matchedAlias}”</span> : null}
                </div>
                <div className="truncate text-xs text-muted">
                  {[kindLabel(hit.kind), hit.parentCityName, hit.districtName, hit.stateName].filter(Boolean).join(" · ")}
                </div>
              </div>
              <span className="hidden shrink-0 sm:block">
                <CoverageBadge status={hit.coverageStatus} />
              </span>
            </li>
          ))}
          {showRequest ? (
            <li role="presentation" className="px-3 py-3 text-sm text-ink-2">
              <p>No indexed destination matches “{searched}”.</p>
              <a
                href={`/request?q=${encodeURIComponent(searched)}`}
                className="mt-1 inline-block font-medium text-accent underline"
                onMouseDown={(e) => e.preventDefault()}
              >
                Request “{searched}” as a destination
              </a>
            </li>
          ) : null}
        </ul>
      ) : null}
    </div>
  );
}
