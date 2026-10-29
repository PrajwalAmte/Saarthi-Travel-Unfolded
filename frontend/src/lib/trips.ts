/** Fewer than this and a weekend-trips page would be thin: it is not shown (or indexed) until more guides exist. */
export const MIN_TRIPS = 5;

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "Nov–Feb", "Jan, Mar": consecutive months (wrapping round the year) shown as ranges. */
export function monthRanges(months: number[]): string {
  if (!months.length) return "";
  const set = new Set(months);
  if (set.size === 12) return "All year";
  const next = (m: number) => (m === 12 ? 1 : m + 1);
  const prev = (m: number) => (m === 1 ? 12 : m - 1);
  return [...set]
    .filter((m) => !set.has(prev(m)))
    .sort((a, b) => a - b)
    .map((start) => {
      let end = start;
      while (set.has(next(end))) end = next(end);
      return end === start ? MONTHS[start - 1] : `${MONTHS[start - 1]}–${MONTHS[end - 1]}`;
    })
    .join(", ");
}
