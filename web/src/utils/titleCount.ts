// The browser tab's unseen count (1.2 D8): a tiny external store the shell writes (the bell's total) and `useDocumentTitle` reads. It holds ZERO whenever the
// number cannot be kept current (signed out, or a socket that is down, paused or idle: `hooks/useTabTitleCount.ts`), so the title never shows a stale one.
import { badgeText } from "./notificationKeys";

let count = 0;
const listeners = new Set<() => void>();

export const getTitleCount = (): number => count;

export function setTitleCount(next: number): void {
  const clean = Number.isFinite(next) && next > 0 ? Math.floor(next) : 0;
  if (clean === count) return;
  count = clean;
  listeners.forEach((listener) => listener());
}

export function subscribeTitleCount(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** The one place the tab title's shape is spelled: "(3) Page · Adder", "(999+) Adder" at the bell's cap, no prefix at 0; a page that is the brand (or none) is the bare brand. */
export function composeTitle(page: string, brand: string, unseen: number): string {
  const prefix = unseen > 0 ? `(${badgeText(unseen)}) ` : "";
  return `${prefix}${page && page !== brand ? `${page} · ${brand}` : brand}`;
}
