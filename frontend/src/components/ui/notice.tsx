import { AlertTriangle, CheckCircle2, Info, XCircle } from "lucide-react";
import * as React from "react";
import { cn } from "@/lib/utils";

type Tone = "info" | "success" | "warning" | "error";

const styles: Record<Tone, { box: string; Icon: typeof Info }> = {
  info: { box: "border-source/20 bg-source-soft text-ink", Icon: Info },
  success: { box: "border-verified/20 bg-verified-soft text-ink", Icon: CheckCircle2 },
  warning: { box: "border-stale/25 bg-stale-soft text-ink", Icon: AlertTriangle },
  error: { box: "border-conflict/25 bg-conflict-soft text-ink", Icon: XCircle },
};

export function Notice({
  tone = "info",
  title,
  children,
  className,
  role,
}: {
  tone?: Tone;
  title?: string;
  children?: React.ReactNode;
  className?: string;
  role?: "status" | "alert";
}) {
  const { box, Icon } = styles[tone];
  return (
    <div className={cn("flex gap-3 rounded-md border p-3 text-sm", box, className)} role={role}>
      <Icon className="mt-0.5 size-4 shrink-0 opacity-80" aria-hidden />
      <div className="min-w-0 space-y-1">
        {title ? <p className="font-medium">{title}</p> : null}
        {children ? <div className="text-ink-2">{children}</div> : null}
      </div>
    </div>
  );
}
