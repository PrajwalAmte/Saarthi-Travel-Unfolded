"use client";

import { useRouter } from "next/navigation";
import * as React from "react";
import { SERVICE_WAKING } from "@/lib/messages";
import { Notice } from "./ui/notice";

const RETRY_SECONDS = 20;
const MAX_RETRIES = 9;

/**
 * Shown when the guide service did not answer, which is normal for a minute or two after it
 * has been idle or restarted. Reloads the page's data by itself every 20 seconds, up to nine
 * times, so the page fills in without the visitor having to do anything.
 */
export function ServiceWaking({ className }: { className?: string }) {
  const router = useRouter();
  const [attempt, setAttempt] = React.useState(0);

  React.useEffect(() => {
    if (attempt >= MAX_RETRIES) return;
    const t = setTimeout(() => {
      router.refresh();
      setAttempt((a) => a + 1);
    }, RETRY_SECONDS * 1000);
    return () => clearTimeout(t);
  }, [attempt, router]);

  return (
    <Notice tone="info" className={className} title={SERVICE_WAKING} role="status">
      {attempt < MAX_RETRIES ? "This page will refresh by itself." : "It is taking longer than usual; please reload in a little while."}
    </Notice>
  );
}
