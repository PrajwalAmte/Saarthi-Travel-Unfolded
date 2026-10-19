"use client";

import { LogOut, ShieldCheck, User } from "lucide-react";
import Link from "next/link";
import { DropdownMenu } from "radix-ui";
import { useSession } from "./session-provider";
import { Button } from "./ui/button";

export function AccountMenu() {
  const { session, loading, signOut } = useSession();
  if (loading) {
    return <span className="skeleton hidden h-8 w-20 sm:inline-block" aria-hidden />;
  }
  if (!session?.authenticated || !session.user) {
    return (
      <Button asChild variant="outline" size="sm" className="hidden sm:inline-flex">
        <Link href="/signin">Contribute</Link>
      </Button>
    );
  }
  const u = session.user;
  const reviewer = u.admin || u.tier === "trusted" || u.tier === "steward";
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <Button variant="ghost" size="sm" aria-label={`Account menu for ${u.displayName}`}>
          <User />
          <span className="hidden max-w-32 truncate sm:inline">{u.displayName}</span>
        </Button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={6}
          className="z-50 min-w-48 rounded-md border border-line bg-surface p-1 text-sm shadow-lg"
        >
          <DropdownMenu.Item asChild className="rounded px-3 py-2 outline-none data-[highlighted]:bg-sunken">
            <Link href="/account">Your contributions</Link>
          </DropdownMenu.Item>
          {reviewer ? (
            <DropdownMenu.Item asChild className="rounded px-3 py-2 outline-none data-[highlighted]:bg-sunken">
              <Link href="/moderation" className="flex items-center gap-2">
                <ShieldCheck className="size-4" /> Review queue
              </Link>
            </DropdownMenu.Item>
          ) : null}
          {u.admin ? (
            <DropdownMenu.Item asChild className="rounded px-3 py-2 outline-none data-[highlighted]:bg-sunken">
              <Link href="/admin">Operations</Link>
            </DropdownMenu.Item>
          ) : null}
          <DropdownMenu.Separator className="my-1 h-px bg-line" />
          <DropdownMenu.Item
            onSelect={() => void signOut()}
            className="flex cursor-pointer items-center gap-2 rounded px-3 py-2 outline-none data-[highlighted]:bg-sunken"
          >
            <LogOut className="size-4" /> Sign out
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
