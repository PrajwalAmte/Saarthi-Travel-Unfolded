"use client";

import { useRouter } from "next/navigation";
import * as React from "react";

/** Re-reads the page's data every 30 seconds (up to 60 times) while a request is still in progress. */
export function AutoRefresh({ active }: { active: boolean }) {
  const router = useRouter();
  React.useEffect(() => {
    if (!active) return;
    let runs = 0;
    const t = setInterval(() => {
      router.refresh();
      if (++runs >= 60) clearInterval(t);
    }, 30_000);
    return () => clearInterval(t);
  }, [active, router]);
  return null;
}
