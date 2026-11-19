/**
 * The thread list's state as URL query parameters, the pure half (the hook is `hooks/useThreadListParams.ts`).
 *
 * Parsing drops junk (an unknown value reads as the default), and writing omits defaults, so a URL only ever spells what differs from
 * the plain list and a shared link means the same list for everyone. Keys that are not the list's (`focus`, `message` and the like,
 * owned by other features) pass through a write untouched. To add a list key: put it in `ThreadListParams`, `DEFAULTS`, `LIST_KEYS`,
 * `parseThreadListParams` and `serialize`; `withListParams` (roomLinks) and the remembered query follow on their own.
 */

export const SCOPES = ["open", "closed", "all"] as const;
export type Scope = (typeof SCOPES)[number];
/** `closed` is the list's only server-side state filter so far: open threads (the default), closed ones, or both (omitted). */
export const SCOPE_FILTER: Record<Scope, boolean | undefined> = { open: false, closed: true, all: undefined };

export const ORDERS = ["activity", "newest", "number"] as const;
export type Order = (typeof ORDERS)[number];
/** Recent activity and newest read from the top; a number counts up from #1. All are on the server's sort whitelist. */
export const ORDER_SORT: Record<Order, string> = { activity: "-lastActivityAt", newest: "-createdAt", number: "number" };

export interface ThreadListParams {
  scope: Scope;
  order: Order;
  /** 1-based. */
  page: number;
}

export const DEFAULT_THREAD_LIST_PARAMS: ThreadListParams = { scope: "open", order: "activity", page: 1 };

/** Every query key the list owns, in the order a URL spells them. */
export const LIST_KEYS = ["scope", "order", "page"] as const;

/** A positive integer of at most nine digits; anything else (`0`, `-1`, `2x`, `1e3`, a huge number) is junk. */
const PAGE = /^[1-9]\d{0,8}$/;

function oneOf<T extends string>(values: readonly T[], raw: string | null, fallback: T): T {
  return values.find((value) => value === raw) ?? fallback;
}

/** The list's state as the URL says it: junk and absent keys read as defaults. A repeated key reads its first value. */
export function parseThreadListParams(search: URLSearchParams): ThreadListParams {
  const page = search.get("page");
  return {
    scope: oneOf(SCOPES, search.get("scope"), DEFAULT_THREAD_LIST_PARAMS.scope),
    order: oneOf(ORDERS, search.get("order"), DEFAULT_THREAD_LIST_PARAMS.order),
    page: page !== null && PAGE.test(page) ? Number(page) : DEFAULT_THREAD_LIST_PARAMS.page,
  };
}

/** Whether the URL spells any list key at all, valid or not (a bare URL is what the remembered query is restored onto). */
export function hasListKeys(search: URLSearchParams): boolean {
  return LIST_KEYS.some((key) => search.has(key));
}

/** The list keys that differ from the defaults, in URL order. */
function serialize(params: ThreadListParams): URLSearchParams {
  const out = new URLSearchParams();
  if (params.scope !== DEFAULT_THREAD_LIST_PARAMS.scope) out.set("scope", params.scope);
  if (params.order !== DEFAULT_THREAD_LIST_PARAMS.order) out.set("order", params.order);
  if (params.page !== DEFAULT_THREAD_LIST_PARAMS.page) out.set("page", String(params.page));
  return out;
}

/**
 * `base` with the list's keys replaced by `patch` laid over what `base` already says. A change of anything but the page goes back to
 * page 1 in the same write (the old page would be read under the new filter); a patch that names the page keeps it. Defaults are left
 * out, junk is dropped, and every key that is not the list's survives.
 */
export function writeThreadListParams(base: URLSearchParams, patch: Partial<ThreadListParams>): URLSearchParams {
  const next = { ...parseThreadListParams(base), ...patch };
  if (patch.page === undefined) next.page = DEFAULT_THREAD_LIST_PARAMS.page;
  const out = new URLSearchParams(base);
  for (const key of LIST_KEYS) out.delete(key);
  for (const [key, value] of serialize(next)) out.append(key, value);
  return out;
}

/** The normalized list keys of `search` (defaults and junk gone), the page included: what an in-room link carries. */
export function listSearch(search: URLSearchParams): URLSearchParams {
  return serialize(parseThreadListParams(search));
}

/** The list keys worth remembering for the room: the page is left out (a stale page 7 is no way to come back), a default list is nothing. */
export function rememberedQuery(search: URLSearchParams): string {
  return serialize({ ...parseThreadListParams(search), page: DEFAULT_THREAD_LIST_PARAMS.page }).toString();
}

/** `search` with the remembered query laid over its other keys, the inverse of `rememberedQuery`: a page in a hand-edited stored value is ignored too. */
export function restoreRememberedQuery(search: URLSearchParams, remembered: string): URLSearchParams {
  return writeThreadListParams(search, { ...parseThreadListParams(new URLSearchParams(remembered)), page: DEFAULT_THREAD_LIST_PARAMS.page });
}
