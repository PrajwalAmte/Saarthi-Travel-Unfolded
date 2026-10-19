"use client";

import { Button } from "@/components/ui/button";

export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div className="container-page max-w-xl py-16" role="alert">
      <h1 className="text-3xl font-semibold">Something went wrong</h1>
      <p className="mt-3 text-ink-2">
        The guide service did not respond as expected. This is usually temporary.
      </p>
      <Button className="mt-6" onClick={reset}>
        Try again
      </Button>
    </div>
  );
}
