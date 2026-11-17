import { useEffect, useRef } from "react";

export type DirtyReader = boolean | (() => boolean);

/**
 * The browser-side half of the cancel guard (Flow's, ported): a `beforeunload` prompt that covers reload/close while the
 * form is dirty. `isDirty` is a value or a fresh reader (a payload compare); the latest one is kept in a ref so the listener
 * never goes stale. Returns the same reader for callers that also gate their own navigation on it (`useDiscardGuard`). The
 * SPA runs a plain `BrowserRouter` (no data router, no `useBlocker`), so in-app departures are guarded by the explicit
 * Cancel confirm only.
 */
export function useBeforeUnloadGuard(isDirty: DirtyReader): () => boolean {
  const dirtyRef = useRef(isDirty);
  useEffect(() => {
    dirtyRef.current = isDirty;
  });
  const dirty = () => (typeof dirtyRef.current === "function" ? dirtyRef.current() : dirtyRef.current);

  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (dirty()) event.preventDefault();
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, []);

  return dirty;
}
