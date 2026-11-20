import { createContext, useContext, useState } from "react";

/**
 * The people's names this session has seen, by user id: the ones a message's `mentions` carry and, later, the ones a pick in the composer
 * chose. It is a plain in-memory map, never persisted and never the authority for a token (a message's own `mentions` is: the SPA follows
 * the server). It exists so a screen that holds only an id (the composer, editing a body that mentions someone) can still say who.
 * One store is provided above the routes, inside the session boundary (`App.tsx`), so a new sign-in starts with none.
 */
export type KnownNames = {
  /** Remembers `people`; a later name for the same id wins (a person may be renamed). */
  remember: (people: readonly { userId: number; name: string }[]) => void;
  /** The last name seen for `userId`, or undefined. */
  get: (userId: number) => string | undefined;
};

export function createKnownNames(): KnownNames {
  const names = new Map<number, string>();
  return {
    remember: (people) => {
      for (const person of people) names.set(person.userId, person.name);
    },
    get: (userId) => names.get(userId),
  };
}

export const KnownNamesContext = createContext<KnownNames | null>(null);

/** The shell's store, or — with no provider (a component rendered alone) — a private one that lives as long as the component. */
export function useKnownNames(): KnownNames {
  const shared = useContext(KnownNamesContext);
  const [own] = useState(createKnownNames);
  return shared ?? own;
}
