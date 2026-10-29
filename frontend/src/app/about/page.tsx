import { BadgeCheck, BookOpen, Map, Users } from "lucide-react";
import Link from "next/link";
import { apiGet } from "@/lib/server-api";
import type { Meta } from "@/lib/types";

export const dynamic = "force-dynamic";
export const metadata = {
  title: "How Saarthi checks its travel information",
  description:
    "Where every fact on Saarthi comes from, how it is dated and checked, and how locals can confirm or correct it.",
  alternates: { canonical: "/about" },
};

const STEPS = [
  {
    Icon: BookOpen,
    title: "We start from open travel guides and maps",
    text: "Wikivoyage's travel writing, OpenStreetMap and other open map data, and Wikidata for every town and district in India.",
  },
  {
    Icon: Users,
    title: "People who know the place keep it current",
    text: "Travellers and locals confirm what's still right, fix what changed and add places that are missing.",
  },
  {
    Icon: BadgeCheck,
    title: "You can always see where a tip comes from",
    text: "Every detail links to its source and date, so you can decide how much to rely on it.",
  },
];

const SOURCES = [
  ["Wikivoyage", "travel guides: places to eat, stay and see, with tips", "CC BY-SA 4.0"],
  ["OpenStreetMap", "places on the map: restaurants, hotels, parks, stations", "ODbL"],
  ["Overture Maps", "more places nearby, matched with OpenStreetMap", "CDLA 2.0 · Apache 2.0 · CC0"],
  ["Wikidata", "every destination, its names in Indian languages, and protected monuments", "CC0"],
  ["Open-Meteo", "ten years of weather, as monthly averages", "CC BY 4.0"],
  ["Wikimedia Commons", "destination photos, credited to their photographers", "per photo"],
];

/** A collapsible section for readers who want the full method. */
function Detail({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <details className="group border-b border-line py-4">
      <summary className="cursor-pointer list-none font-medium text-ink marker:hidden">
        <span className="mr-2 inline-block text-accent transition-transform group-open:rotate-90">›</span>
        {title}
      </summary>
      <div className="mt-3 space-y-3 pl-5 text-ink-2 [&_li]:mt-1 [&_ul]:list-disc [&_ul]:pl-5">{children}</div>
    </details>
  );
}

export default async function AboutPage() {
  const meta = await apiGet<Meta>("/api/meta").catch(() => null);
  const w = meta?.reviewWindowsDays;
  return (
    <div className="container-page max-w-4xl py-12">
      <h1 className="text-4xl font-semibold sm:text-5xl">How Saarthi works</h1>
      <p className="mt-4 max-w-2xl text-lg text-ink-2">
        A free travel guide to India, made from open data and improved by people who know each place. No ads, no paid
        rankings, nothing made up.
      </p>

      <ol className="mt-10 grid gap-5 sm:grid-cols-3">
        {STEPS.map(({ Icon, title, text }, i) => (
          <li key={title} className="rounded-xl border border-line bg-surface p-6">
            <div className="flex items-center gap-3">
              <span className="flex size-10 items-center justify-center rounded-full bg-accent-soft text-accent">
                <Icon className="size-5" aria-hidden />
              </span>
              <span className="text-sm font-semibold text-muted">Step {i + 1}</span>
            </div>
            <h2 className="mt-4 text-xl font-semibold leading-snug">{title}</h2>
            <p className="mt-2 text-ink-2">{text}</p>
          </li>
        ))}
      </ol>

      <section className="mt-14">
        <h2 className="text-2xl font-semibold">Where the information comes from</h2>
        <ul className="mt-4 divide-y divide-line rounded-xl border border-line bg-surface">
          {SOURCES.map(([name, what, licence]) => (
            <li key={name} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-5 py-3.5">
              <span>
                <span className="font-medium text-ink">{name}</span> <span className="text-ink-2">— {what}</span>
              </span>
              <span className="text-sm text-muted">{licence}</span>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-sm text-muted">
          Every import is checked for quality before it appears.{" "}
          <Link href="/sources" className="text-accent underline">
            See each source&apos;s status
          </Link>
          .
        </p>
      </section>

      <section className="mt-14 grid gap-6 sm:grid-cols-2">
        <div>
          <Map className="size-6 text-accent" aria-hidden />
          <h2 className="mt-3 text-2xl font-semibold">How places are ordered</h2>
          <p className="mt-2 text-ink-2">
            The same way for everyone: places that match your preferences, are well documented and recently confirmed come
            first. Nobody can pay to move up. Every place has a “Why it&apos;s listed” note.
          </p>
        </div>
        <div>
          <Users className="size-6 text-accent" aria-hidden />
          <h2 className="mt-3 text-2xl font-semibold">Know a place? Join in</h2>
          <p className="mt-2 text-ink-2">
            Reading needs no account. Sign in to confirm details, fix mistakes or add a place you love. New contributors&apos;
            edits are checked before they go live.
          </p>
          <Link href="/signin" className="mt-3 inline-block font-medium text-accent underline">
            Start contributing
          </Link>
        </div>
      </section>

      <section className="mt-14">
        <h2 className="text-2xl font-semibold">The details</h2>
        <p className="mt-2 text-ink-2">For anyone who wants to know exactly how it works.</p>
        <div className="mt-4 border-t border-line">
          <Detail title="How we keep information fresh">
            <p>Each detail is checked again after a set time, depending on how quickly that kind of thing changes:</p>
            <ul>
              <li>Prices and opening hours: every {w?.volatile ?? 120} days.</li>
              <li>Recommendations, addresses and transport: every {w?.semi_stable ?? 365} days.</li>
              <li>Local specialities, customs and neighbourhood character: every {w?.stable ?? 1095} days.</li>
            </ul>
            <p>
              The clock runs from when the source last saw it or a local last confirmed it, whichever is later. Older prices
              and hours are marked with their date; if someone reports a detail as changed, it says so until it&apos;s confirmed again.
            </p>
          </Detail>
          <Detail title="Mapped places versus guide recommendations">
            <p>
              Guide entries come from travel writing and contributors. “More places nearby” comes from open map data: useful
              for finding what&apos;s around, but not reviewed picks. When two map sources describe the same place, they are
              matched by name, distance and contact details, and each match records the rule that made it.
            </p>
          </Detail>
          <Detail title="Who can change what">
            <ul>
              <li>Anyone can read without an account.</li>
              <li>Signed-in contributors&apos; corrections and new places are reviewed before they go live.</li>
              <li>Contributors with a good track record become trusted: their edits publish directly.</li>
              <li>City stewards, appointed by maintainers, review closures, high-impact changes and reports.</li>
              <li>Business owners can contribute but must say so; their edits are always reviewed and labelled.</li>
            </ul>
            <p>Every change is recorded, and conflicting reports are kept rather than overwritten.</p>
          </Detail>
          <Detail title="How a guide grows">
            <p>
              A guide is “in progress” until it has a summary and several places in most sections, and a “full guide” once every
              section is well stocked and locals have confirmed a good share of it. When locals have confirmed most of a guide,
              they look after it from then on and it no longer takes updates from the collected sources.
            </p>
          </Detail>
          <Detail title="Licences and privacy">
            <p>
              The software is open source (Apache 2.0). Content keeps the licence of its source, and contributions are shared
              under CC BY-SA 4.0. <Link href="/sources" className="text-accent underline">Sources and licences</Link> ·{" "}
              <Link href="/privacy" className="text-accent underline">Privacy and data retention</Link>
            </p>
          </Detail>
        </div>
      </section>
    </div>
  );
}
