"use client";

import { X } from "lucide-react";
import { Dialog as D } from "radix-ui";
import * as React from "react";
import { cn } from "@/lib/utils";

export const Dialog = D.Root;
export const DialogTrigger = D.Trigger;
export const DialogClose = D.Close;

export function DialogContent({
  title,
  description,
  children,
  className,
  side,
}: {
  title: string;
  description?: string;
  children: React.ReactNode;
  className?: string;
  side?: "right";
}) {
  return (
    <D.Portal>
      <D.Overlay className="fixed inset-0 z-40 bg-ink/40 backdrop-blur-[1px]" />
      <D.Content
        className={cn(
          "fixed z-50 bg-surface text-ink shadow-xl focus:outline-none",
          side === "right"
            ? "inset-y-0 right-0 w-[min(22rem,90vw)] border-l border-line p-5"
            : "left-1/2 top-1/2 max-h-[90dvh] w-[min(34rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-lg border border-line p-5 sm:p-6",
          className,
        )}
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <div>
            <D.Title className="font-serif text-xl font-semibold">{title}</D.Title>
            {description ? <D.Description className="mt-1 text-sm text-muted">{description}</D.Description> : null}
          </div>
          <D.Close
            className="-mr-2 -mt-1 inline-flex h-9 w-9 items-center justify-center rounded-md text-muted hover:bg-sunken hover:text-ink"
            aria-label="Close"
          >
            <X className="size-4" />
          </D.Close>
        </div>
        {children}
      </D.Content>
    </D.Portal>
  );
}
