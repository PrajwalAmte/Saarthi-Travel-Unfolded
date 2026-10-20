"use client";

import { useRouter } from "next/navigation";
import * as React from "react";
import { Button } from "@/components/ui/button";
import { SERVICE_WAKING } from "@/lib/messages";

const RETRY_SECONDS = 15;
const MAX_RETRIES = 6;

/**
 * Shown when a page fails to render on the server, usually because the guide service was busy or
 * waking up. Retrying must ask the server again (router.refresh) and then reset this boundary;
 * resetting alone would only re-show the same failed result. It also retries by itself a few times.
 */
export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const router = useRouter();
  const [attempt, setAttempt] = React.useState(0);
  const [, startTransition] = React.useTransition();

  const retry = React.useCallback(() => {
    startTransition(() => {
      router.refresh();
      reset();
    });
  }, [router, reset]);

  React.useEffect(() => {
    if (attempt >= MAX_RETRIES) return;
    const t = setTimeout(() => {
      setAttempt((a) => a + 1);
      retry();
    }, RETRY_SECONDS * 1000);
    return () => clearTimeout(t);
  }, [attempt, retry]);

  return (
    <div className="container-page max-w-xl py-16" role="alert">
      <h1 className="text-3xl font-semibold">This page could not load</h1>
      <p className="mt-3 text-ink-2">{SERVICE_WAKING}</p>
      <p className="mt-1 text-sm text-muted">
        {attempt < MAX_RETRIES ? "It will try again by itself." : "It is taking longer than usual; please try again in a little while."}
      </p>
      <Button className="mt-6" onClick={retry}>
        Try again
      </Button>
    </div>
  );
}
