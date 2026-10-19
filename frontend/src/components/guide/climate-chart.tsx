import type { ClimateView } from "@/lib/types";

const MONTHS = ["J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"];
const MONTH_NAMES = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

/** Monthly rainfall bars with average daily high/low temperatures, from stored aggregates. */
export function ClimateChart({ climate }: { climate: ClimateView }) {
  const months = [...climate.months].sort((a, b) => a.month - b.month);
  const maxRain = Math.max(50, ...months.map((m) => m.precipitationMm ?? 0));
  const temps = months.flatMap((m) => [m.meanMaxC ?? 0, m.meanMinC ?? 0]);
  const tMax = Math.ceil(Math.max(...temps) / 5) * 5;
  const tMin = Math.floor(Math.min(...temps) / 5) * 5;
  const W = 600;
  const H = 220;
  const pad = { l: 34, r: 34, t: 12, b: 26 };
  const cw = (W - pad.l - pad.r) / 12;
  const y = (t: number) => pad.t + (1 - (t - tMin) / Math.max(1, tMax - tMin)) * (H - pad.t - pad.b);
  const rainY = (r: number) => H - pad.b - (r / maxRain) * (H - pad.t - pad.b);
  const line = (key: "meanMaxC" | "meanMinC") =>
    months.map((m, i) => `${i === 0 ? "M" : "L"}${pad.l + cw * i + cw / 2},${y(m[key] ?? 0)}`).join(" ");

  return (
    <figure className="space-y-2">
      <svg viewBox={`0 0 ${W} ${H}`} className="h-auto w-full" role="img" aria-labelledby="climate-title climate-desc">
        <title id="climate-title">Average monthly climate {climate.period}</title>
        <desc id="climate-desc">
          {months
            .map((m) => `${MONTH_NAMES[m.month - 1]}: high ${m.meanMaxC} degrees, low ${m.meanMinC} degrees, ${m.precipitationMm} millimetres of rain`)
            .join("; ")}
        </desc>
        {months.map((m, i) => (
          <rect
            key={m.month}
            x={pad.l + cw * i + cw * 0.18}
            width={cw * 0.64}
            y={rainY(m.precipitationMm ?? 0)}
            height={H - pad.b - rainY(m.precipitationMm ?? 0)}
            fill="var(--color-source-soft)"
            stroke="var(--color-source)"
            strokeOpacity={0.35}
          />
        ))}
        <path d={line("meanMaxC")} fill="none" stroke="var(--color-conflict)" strokeWidth={2} />
        <path d={line("meanMinC")} fill="none" stroke="var(--color-accent)" strokeWidth={2} />
        {months.map((m, i) => (
          <text key={m.month} x={pad.l + cw * i + cw / 2} y={H - 8} textAnchor="middle" fontSize={11} fill="var(--color-muted)">
            {MONTHS[m.month - 1]}
          </text>
        ))}
        {[tMin, (tMin + tMax) / 2, tMax].map((t) => (
          <text key={t} x={pad.l - 6} y={y(t) + 4} textAnchor="end" fontSize={10} fill="var(--color-muted)">
            {t}°
          </text>
        ))}
        <text x={W - pad.r + 6} y={rainY(maxRain) + 4} fontSize={10} fill="var(--color-muted)">
          {Math.round(maxRain)}mm
        </text>
      </svg>
      <figcaption className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block h-0.5 w-4 bg-conflict" aria-hidden /> Average daily high
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block h-0.5 w-4 bg-accent" aria-hidden /> Average daily low
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="inline-block h-3 w-3 border border-source/40 bg-source-soft" aria-hidden /> Average monthly rainfall
        </span>
        <span>{climate.period ? `Averages ${climate.period}` : null}</span>
      </figcaption>
    </figure>
  );
}
