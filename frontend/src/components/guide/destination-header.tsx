import { ChevronRight } from "lucide-react";
import Link from "next/link";
import type { DestinationDetail } from "@/lib/types";
import { CoverageBadge, MATURITY_NOTE } from "../coverage-badge";
import { formatDate, kindLabel, safeHref } from "@/lib/utils";

export function DestinationHeader({ d }: { d: DestinationDetail }) {
  const state = d.parents.find((p) => p.relation === "in_state");
  const district = d.parents.find((p) => p.relation === "in_district");
  const city = d.parents.find((p) => p.relation === "part_of_city");
  const latinAliases = d.aliases.filter((a) => /^[\p{Script=Latin}\s\-'.()]+$/u.test(a.alias)).slice(0, 4);
  const localAliases = d.aliases.filter((a) => a.type === "local_script").slice(0, 3);

  return (
    <header className="pb-6 pt-6 sm:pt-8">
      <nav aria-label="Breadcrumb" className="text-sm text-muted">
        <ol className="flex flex-wrap items-center gap-1">
          <li>
            <Link href="/" className="hover:text-ink hover:underline">
              Home
            </Link>
          </li>
          {[state, district, city].filter(Boolean).map((p) => (
            <li key={p!.slug} className="flex items-center gap-1">
              <ChevronRight className="size-3.5" aria-hidden />
              <Link href={`/d/${p!.slug}`} className="hover:text-ink hover:underline">
                {p!.name}
              </Link>
            </li>
          ))}
        </ol>
      </nav>

      <div className="mt-4 grid gap-6 md:grid-cols-[1fr_auto] md:items-start">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted">
            <span>{kindLabel(d.kind)}</span>
            {d.description ? <span aria-hidden>·</span> : null}
            {d.description ? <span>{d.description}</span> : null}
          </div>
          <h1 className="mt-1 text-4xl font-semibold leading-tight sm:text-5xl">{d.name}</h1>
          <p className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-ink-2">
            <CoverageBadge status={d.coverage.status} />
            <span>{MATURITY_NOTE[d.coverage.status]}</span>
            {d.guideAvailable ? (
              <Link href={`/d/${d.slug}/recheck`} className="text-accent underline">
                Help check it
              </Link>
            ) : null}
          </p>
          {latinAliases.length || localAliases.length ? (
            <p className="mt-2 text-sm text-ink-2">
              Also known as{" "}
              {[...latinAliases.map((a) => a.alias), ...localAliases.map((a) => a.alias)].map((a, i, arr) => (
                <span key={a}>
                  <span lang="und">{a}</span>
                  {i < arr.length - 1 ? ", " : ""}
                </span>
              ))}
            </p>
          ) : null}

          <p className="mt-3 text-xs text-muted">
            {d.sourcesUsed.length ? `From ${d.sourcesUsed.map((s) => s.name).join(", ")}` : ""}
            {d.lastSourceSyncAt ? ` · updated ${formatDate(d.lastSourceSyncAt)}` : ""}
            {d.wikivoyageUrl ? (
              <>
                {" · "}
                <a href={d.wikivoyageUrl} className="underline" target="_blank" rel="noopener noreferrer">
                  Wikivoyage article
                </a>
              </>
            ) : null}
            {d.wikidataUrl ? (
              <>
                {" · "}
                <a href={d.wikidataUrl} className="underline" target="_blank" rel="noopener noreferrer">
                  Wikidata
                </a>
              </>
            ) : null}
          </p>
        </div>

        {d.image && safeHref(d.image.url) ? (
          <figure className="w-full md:w-80">
            {/* Hot-linked from Wikimedia with its licence credit, as Commons reuse requires. */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={d.image.url}
              alt={`Photograph associated with ${d.name} on Wikidata`}
              width={d.image.width ?? undefined}
              height={d.image.height ?? undefined}
              loading="lazy"
              className="aspect-[4/3] w-full rounded-md object-cover"
            />
            <figcaption className="mt-1.5 text-xs text-muted">
              {d.image.artist ? `${d.image.artist}, ` : ""}
              {d.image.licenceUrl ? (
                <a href={d.image.licenceUrl} className="underline" target="_blank" rel="noopener noreferrer">
                  {d.image.licenceName}
                </a>
              ) : (
                d.image.licenceName
              )}
              {" · "}
              <a href={d.image.pageUrl} className="underline" target="_blank" rel="noopener noreferrer">
                {d.image.sourceName}
              </a>
            </figcaption>
          </figure>
        ) : null}
      </div>
    </header>
  );
}
