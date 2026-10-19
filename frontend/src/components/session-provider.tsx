"use client";

import * as React from "react";
import { api } from "@/lib/client-api";
import type { SessionView } from "@/lib/types";

type SessionState = {
  session: SessionView | null;
  loading: boolean;
  refresh: () => Promise<void>;
  signOut: () => Promise<void>;
};

const SessionContext = React.createContext<SessionState | null>(null);

/** Loads the contributor session (if any) once per page load; readers never need it. */
export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = React.useState<SessionView | null>(null);
  const [loading, setLoading] = React.useState(true);

  const refresh = React.useCallback(async () => {
    try {
      setSession(await api<SessionView>("/api/auth/session"));
    } catch {
      setSession(null);
    } finally {
      setLoading(false);
    }
  }, []);

  const signOut = React.useCallback(async () => {
    await api<void>("/api/auth/logout", { method: "POST" });
    await refresh();
  }, [refresh]);

  React.useEffect(() => {
    let cancelled = false;
    api<SessionView>("/api/auth/session")
      .then((s) => {
        if (!cancelled) setSession(s);
      })
      .catch(() => {
        if (!cancelled) setSession(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const value = React.useMemo(() => ({ session, loading, refresh, signOut }), [session, loading, refresh, signOut]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  const ctx = React.useContext(SessionContext);
  if (!ctx) {
    throw new Error("useSession must be used inside SessionProvider");
  }
  return ctx;
}
