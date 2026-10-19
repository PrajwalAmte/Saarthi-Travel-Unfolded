"use client";

import { Menu } from "lucide-react";
import Link from "next/link";
import * as React from "react";
import { Button } from "./ui/button";
import { Dialog, DialogContent, DialogTrigger } from "./ui/dialog";

export function MobileNav({ items }: { items: { href: string; label: string }[] }) {
  const [open, setOpen] = React.useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="ghost" size="icon" className="md:hidden" aria-label="Open menu">
          <Menu />
        </Button>
      </DialogTrigger>
      <DialogContent title="Menu" side="right">
        <nav aria-label="Mobile" className="flex flex-col">
          {items.map((n) => (
            <Link key={n.href} href={n.href} onClick={() => setOpen(false)} className="border-b border-line py-3 text-base text-ink hover:text-accent">
              {n.label}
            </Link>
          ))}
          <Link href="/account" onClick={() => setOpen(false)} className="py-3 text-base text-ink hover:text-accent">
            Contributor account
          </Link>
        </nav>
      </DialogContent>
    </Dialog>
  );
}
