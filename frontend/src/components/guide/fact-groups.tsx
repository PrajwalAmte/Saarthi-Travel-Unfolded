import type { FactGroup } from "@/lib/types";
import { FactCard } from "./fact-card";

export function FactGroups({ groups, destinationId }: { groups: FactGroup[]; destinationId: string }) {
  return (
    <div className="space-y-10">
      {groups.map((g) => (
        <section key={g.key} aria-labelledby={`grp-${g.key}`}>
          <h2 id={`grp-${g.key}`} className="text-2xl font-semibold">
            {g.title}
          </h2>
          <div className="mt-2">
            {g.facts.map((f) => (
              <FactCard key={f.id} fact={f} destinationId={destinationId} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
