import { useEffect, useRef, useState } from "react";
import { useLocation } from "react-router-dom";

/** A thread's own route: its pane (or the message it jumps to) takes the focus itself after a navigation, so nothing here should. */
const THREAD_ROUTE = /^\/rooms\/\d+\/threads\/\d+$/;

/**
 * A drawer is a modal over the page, so a change of page while it is open (a person's, a room's or a team's link inside it, 1.2 D6) must close it
 * rather than leave it covering the destination. `onLeave` closes it WITHOUT returning focus to its trigger: the trigger may not exist on the
 * destination (the room page's "Pinned" button), and a focus handed back to it would pull focus over the page the person just opened. Focus goes
 * where a link's destination would put it: the destination's own pane for a thread route (which takes focus itself after the navigation), the
 * main region (`#main-content`, the skip link's target) for any other page, so it never falls to `<body>` and a keyboard user starts at the top
 * of the new page. A drawer's own links that already close it (a thread, a message) never reach here: the page has not changed when they do.
 *
 * Returns `noteOpened`: call it when the drawer opens, so the page it was opened on is the one compared. Adjusted during render, like the drawers'
 * other derived state; the focus is an effect, run once the drawer has closed and released its focus trap.
 */
export function useCloseOnPageChange(opened: boolean, onLeave: () => void): () => void {
  const { pathname } = useLocation();
  const [openedAt, setOpenedAt] = useState<string | null>(null);
  // Each page change that closed the drawer is one numbered request; the effect answers each once, when the drawer has closed.
  const [left, setLeft] = useState({ count: 0, toMain: false });
  const answered = useRef(0);
  if (opened && openedAt !== null && pathname !== openedAt) {
    onLeave();
    setLeft({ count: left.count + 1, toMain: !THREAD_ROUTE.test(pathname) });
  }
  useEffect(() => {
    if (opened || answered.current === left.count) return;
    answered.current = left.count;
    if (left.toMain) document.getElementById("main-content")?.focus();
  }, [left, opened]);
  return () => setOpenedAt(pathname);
}
