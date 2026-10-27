import Link from "next/link";
import type { BudgetRange, BudgetView } from "@/lib/types";
import { formatInr, formatMonth } from "@/lib/utils";
import { Notice } from "../ui/notice";

function rangeText(r: BudgetRange): string {
  return r.low === r.high ? formatInr(r.low) : `${formatInr(r.low)} – ${formatInr(r.high)}`;
}

function observed(r: BudgetRange): string {
  if (!r.earliestObserved || !r.latestObserved) return "Dates not recorded";
  return r.earliestObserved === r.latestObserved
    ? (formatMonth(r.earliestObserved) ?? "Dates not recorded")
    : `${formatMonth(r.earliestObserved)} – ${formatMonth(r.latestObserved)}`;
}

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
  const usable = data.ranges.filter((r) => r.reliability === "ok" || r.reliability === "mostly_stale");
  const thin = data.ranges.filter((r) => r.reliability === "too_few" || r.reliability === "all_stale");
  return (
    <div className="space-y-6">
      {usable.length ? (
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
              {usable.map((r) => (
                <tr key={r.category} className="border-t border-line align-top">
                  <th scope="row" className="px-4 py-3 font-medium text-ink">
                    {r.label}
                    <div className="text-xs font-normal text-muted">{r.unit}</div>
                  </th>
                  <td className="px-4 py-3 tabular-nums">{rangeText(r)}</td>
                  <td className="px-4 py-3 tabular-nums">{formatInr(r.median)}</td>
                  <td className="px-4 py-3">
                    {r.observations} listing{r.observations === 1 ? "" : "s"}
                    {r.needingReverification > 0 ? (
                      <div className={r.reliability === "mostly_stale" ? "text-xs text-conflict" : "text-xs text-muted"}>
                        {r.needingReverification} of {r.observations} need rechecking
                      </div>
                    ) : null}
                  </td>
                  <td className="px-4 py-3 text-xs text-muted">{observed(r)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {thin.length ? (
        <section aria-label="Not enough recent prices">
          <Notice tone="info" title="Not enough recent prices for an estimate">
            For these categories we have too few listings, or every price we have is out of date, so we do not show a typical range.
            What we do have is listed below for reference only. Know the place?{" "}
            <Link href={`/d/${slug}/recheck`} className="underline">
              Help check its prices
            </Link>
            .
          </Notice>
          <ul className="mt-3 divide-y divide-line rounded-lg border border-line text-sm text-ink-2">
            {thin.map((r) => (
              <li key={r.category} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-4 py-2.5">
                <span className="font-medium text-ink">{r.label}</span>
                <span className="text-muted">
                  {r.observations} price{r.observations === 1 ? "" : "s"} ({rangeText(r)}), {observed(r).toLowerCase()}
                  {r.reliability === "all_stale" ? ", all need rechecking" : ""}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

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
