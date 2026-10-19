import type { Fact } from "@/lib/types";
import { FactMeta } from "./fact-meta";

/** A destination-level fact (transport, tips, specialities, notes). */
export function FactCard({ fact, destinationId, title }: { fact: Fact; destinationId: string; title?: string | null }) {
  const heading = title ?? fact.heading;
  return (
    <article className="border-b border-line py-4 last:border-b-0">
      {heading ? <h3 className="font-serif text-lg font-semibold">{heading}</h3> : null}
      <p className="mt-1 whitespace-pre-line text-[0.95rem] leading-relaxed text-ink-2">{fact.text}</p>
      <FactMeta fact={fact} destinationId={destinationId} />
    </article>
  );
}
