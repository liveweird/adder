import { createContext, useContext, useMemo, useState, useSyncExternalStore } from "react";
import { getSessionSnapshot } from "../api/session";
import { subscribeLogicalSession } from "./sessionQueryCache";

/**
 * The people's names this session has seen, by user id (the ones a message's `mentions` carry and the ones a pick in the composer chose), and
 * the teams' handles, by team id (a message's `groupMentions` and a team pick). It is a plain in-memory map, never persisted and never the authority for a token (a message's own `mentions` is: the SPA follows
 * the server). It exists so a screen that holds only an id (the composer, editing a body that mentions someone) can still say who.
 * One store is provided above the routes, inside the session boundary (`App.tsx`), so a new sign-in starts with none.
 */
export type KnownNames = {
  /** Remembers `people`; a later name for the same id wins (a person may be renamed). */
  remember: (people: readonly { userId: number; name: string }[]) => void;
  /** The last name seen for `userId`, or undefined. */
  get: (userId: number) => string | undefined;
  /** Remembers the handles of `teams`; a later handle for the same id wins (a team may be renamed). */
  rememberTeams: (teams: readonly { teamId: number | null; teamHandle: string | null }[]) => void;
  /** The last handle seen for team `teamId`, or undefined. */
  getTeam: (teamId: number) => string | undefined;
};

export function createKnownNames(): KnownNames {
  const names = new Map<number, string>();
  const handles = new Map<number, string>();
  return {
    remember: (people) => {
      for (const person of people) names.set(person.userId, person.name);
    },
    get: (userId) => names.get(userId),
    rememberTeams: (teams) => {
      for (const team of teams) if (team.teamId !== null && team.teamHandle !== null) handles.set(team.teamId, team.teamHandle);
    },
    getTeam: (teamId) => handles.get(teamId),
  };
}

export const KnownNamesContext = createContext<KnownNames | null>(null);

/**
 * The shell's store, or — with no provider (a component rendered alone) — a private one that lives as long as the component. The private
 * one is allocated only when it is needed, so a reader under the shell (every `MarkdownView`) allocates nothing.
 */
export function useKnownNames(): KnownNames {
  const shared = useContext(KnownNamesContext);
  // Allocated only without a provider (`useMemo` is a cache here, never a guarantee the store must survive: a component alone is a test's case).
  const own = useMemo(() => (shared === null ? createKnownNames() : null), [shared]);
  return shared ?? own ?? createKnownNames();
}

function currentIdentity(): string | null {
  return getSessionSnapshot().identity;
}

/**
 * The shell's store, tied to the logical session: a sign-out, a replacement login or another tab signing in as someone else (the stored
 * identity changes) hands back a NEW empty store, so one person's names never show to the next. (`SessionBoundary` remounts the shell on
 * the same change; this holds even where the shell stays mounted, because the store must not outlive the account it was filled for.)
 */
export function useSessionKnownNames(): KnownNames {
  const identity = useSyncExternalStore(subscribeLogicalSession, currentIdentity, () => null);
  const [held, setHeld] = useState(() => ({ identity, names: createKnownNames() }));
  if (held.identity === identity) return held.names;
  const next = { identity, names: createKnownNames() };
  setHeld(next);
  return next.names;
}
