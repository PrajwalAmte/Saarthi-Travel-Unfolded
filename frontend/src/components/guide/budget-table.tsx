import Link from "next/link";
import type { BudgetView } from "@/lib/types";
import { formatInr, formatMonth } from "@/lib/utils";
import { Notice } from "../ui/notice";

export function BudgetTable({ data, slug }: { data: BudgetView; slug: string }) {
  if (data.emptyReason) {
    return (
      <div className="space-y-4">
        <Notice tone="info" title="No budget estimate yet">
          {data.emptyReason} Budget ranges are only shown when real prices have been recorded.
        </Notice>
        <p className="text-sm text-ink-2">
          Know current prices?{" "}
          <Link href={`/d/${slug}/eat`} className="text-accent underline">
            Open a listing and use “Still true?” to update its price
          </Link>
          .
        </p>
      </div>
    );
  }
  return (
    <div className="space-y-6">
      <div className="overflow-x-auto rounded-lg border border-line">
        <table className="w-full min-w-[36rem] text-left text-sm">
          <caption className="sr-only">Price ranges derived from recorded listing prices</caption>
          <thead className="bg-sunken/80 text-xs uppercase tracking-wide text-muted">
            <tr>
              <th scope="col" className="px-4 py-3 font-medium">Category</th>
              <th scope="col" className="px-4 py-3 font-medium">Typical range</th>
              <th scope="col" className="px-4 py-3 font-medium">Median</th>
              <th scope="col" className="px-4 py-3 font-medium">Based on</th>
              <th scope="col" className="px-4 py-3 font-medium">Observed</th>
            </tr>
          </thead>
          <tbody>
            {data.ranges.map((r) => (
              <tr key={r.category} className="border-t border-line align-top">
                <th scope="row" className="px-4 py-3 font-medium text-ink">
                  {r.label}
                  <div className="text-xs font-normal text-muted">{r.unit}</div>
                </th>
                <td className="px-4 py-3 tabular-nums">
                  {r.low === r.high ? formatInr(r.low) : `${formatInr(r.low)} – ${formatInr(r.high)}`}
                  {r.observations < 3 ? <div className="text-xs text-muted">Few prices: full range shown</div> : null}
                </td>
                <td className="px-4 py-3 tabular-nums">{formatInr(r.median)}</td>
                <td className="px-4 py-3">
                  {r.observations} listing{r.observations === 1 ? "" : "s"}
                  {r.needingReverification > 0 ? (
                    <div className="text-xs text-muted">{r.needingReverification} may be dated</div>
                  ) : null}
                </td>
                <td className="px-4 py-3 text-xs text-muted">
                  {r.earliestObserved && r.latestObserved
                    ? r.earliestObserved === r.latestObserved
                      ? formatMonth(r.earliestObserved)
                      : `${formatMonth(r.earliestObserved)} – ${formatMonth(r.latestObserved)}`
                    : "Dates not recorded"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <section>
        <h2 className="text-lg font-semibold">Assumptions</h2>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-ink-2">
          {data.assumptions.map((a) => (
            <li key={a}>{a}</li>
          ))}
        </ul>
        <p className="mt-3 text-xs text-muted">
          {data.priceFactsConsidered} recorded prices considered
          {data.priceFactsWithoutAmount ? `, ${data.priceFactsWithoutAmount} without a comparable rupee amount` : ""}. These are
          estimates for planning, not quotes.
        </p>
      </section>
    </div>
  );
}
