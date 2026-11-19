/**
 * The thread list's state as URL query parameters, the pure half (the hook is `hooks/useThreadListParams.ts`).
 *
 * Parsing drops junk (an unknown value reads as the default), and writing omits defaults, so a URL only ever spells what differs from
 * the plain list and a shared link means the same list for everyone. Keys that are not the list's (`focus`, `message` and the like,
 * owned by other features) pass through a write untouched. To add a list key: put it in `ThreadListParams`, `DEFAULTS`, `LIST_KEYS`,
 * `parseThreadListParams` and `serialize`; `withListParams` (roomLinks) and the remembered query follow on their own.
 * `toApiQuery` turns the state into the request (resolving `owner=me`, dropping a stale `field` key).
 */

import type { RoomWorkflow } from "../api/rooms";

export const SCOPES = ["open", "closed", "all"] as const;
export type Scope = (typeof SCOPES)[number];
/** `closed` is the category filter: open threads (the default), closed ones, or both (omitted). */
export const SCOPE_FILTER: Record<Scope, boolean | undefined> = { open: false, closed: true, all: undefined };

export const ORDERS = ["activity", "newest", "number", "status"] as const;
export type Order = (typeof ORDERS)[number];
/** Recent activity and newest read from the top; a number counts up from #1; `status` follows the room's workflow order. All are on the server's sort whitelist. */
export const ORDER_SORT: Record<Order, string> = { activity: "-lastActivityAt", newest: "-createdAt", number: "number", status: "status" };

/** The most distinct values of `status` and of `field` the server accepts in one request. */
const MAX_FILTER_VALUES = 20;

export interface ThreadListParams {
  scope: Scope;
  order: Order;
  /** Any-of these status keys (empty = no filter). */
  status: string[];
  /** One owner: `me` (resolved to the session's id where the request is built, so a shared link means "my threads" for whoever opens it) or a user id. */
  owner: string | null;
  /** `key:value` pairs: any-of within a field key, all-of across keys. Judged against the room's workflow where the request is built. */
  field: string[];
  /** 1-based. */
  page: number;
}

export const DEFAULT_THREAD_LIST_PARAMS: ThreadListParams = { scope: "open", order: "activity", status: [], owner: null, field: [], page: 1 };

/** Every query key the list owns, in the order a URL spells them. */
const LIST_KEYS = ["scope", "order", "status", "owner", "field", "page"] as const;

/** A positive integer of at most nine digits; anything else (`0`, `-1`, `2x`, `1e3`, a huge number) is junk. */
const PAGE = /^[1-9]\d{0,8}$/;

/** The server's key grammar (`KEY_GRAMMAR`, at most 40 characters): a status key, a field key, an option key. */
const KEY = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/;
const KEY_MAX_LENGTH = 40;
const isKey = (raw: string) => raw.length <= KEY_MAX_LENGTH && KEY.test(raw);
/** A user id as the server takes it: ASCII digits, no leading zero, within int32. */
const isUserId = (raw: string) => /^[1-9]\d{0,9}$/.test(raw) && Number(raw) <= 2_147_483_647;
/** `key:value`: a field key, a colon, then an option key (SELECT, MULTI_SELECT) or a user id (USER). */
function isFieldEntry(raw: string): boolean {
  const colon = raw.indexOf(":");
  return colon > 0 && isKey(raw.slice(0, colon)) && (isKey(raw.slice(colon + 1)) || isUserId(raw.slice(colon + 1)));
}

/** The valid, distinct values of a repeated key, in URL order, at most [MAX_FILTER_VALUES]. */
function repeated(search: URLSearchParams, name: string, valid: (raw: string) => boolean): string[] {
  return [...new Set(search.getAll(name).filter(valid))].slice(0, MAX_FILTER_VALUES);
}

function oneOf<T extends string>(values: readonly T[], raw: string | null, fallback: T): T {
  return values.find((value) => value === raw) ?? fallback;
}

/** The list's state as the URL says it: junk and absent keys read as defaults. A repeated scalar key reads its first value; `status` and `field` keep every valid value. */
export function parseThreadListParams(search: URLSearchParams): ThreadListParams {
  const page = search.get("page");
  const owner = search.get("owner");
  return {
    scope: oneOf(SCOPES, search.get("scope"), DEFAULT_THREAD_LIST_PARAMS.scope),
    order: oneOf(ORDERS, search.get("order"), DEFAULT_THREAD_LIST_PARAMS.order),
    status: repeated(search, "status", isKey),
    owner: owner !== null && (owner === "me" || isUserId(owner)) ? owner : DEFAULT_THREAD_LIST_PARAMS.owner,
    field: repeated(search, "field", isFieldEntry),
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
  for (const status of params.status) out.append("status", status);
  if (params.owner !== DEFAULT_THREAD_LIST_PARAMS.owner) out.set("owner", String(params.owner));
  for (const entry of params.field) out.append("field", entry);
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

/** The field types a thread filter can judge: an option key (SELECT, MULTI_SELECT) or a user id (USER). The server answers 400 for every other type. */
const FILTERABLE_FIELD_TYPES: readonly string[] = ["SELECT", "MULTI_SELECT", "USER"];
type WorkflowField = RoomWorkflow["fields"][number];

/** The room's fields a filter control is offered for: the workflow's ACTIVE SELECT, MULTI_SELECT and USER fields. */
export function filterableFields(workflow: RoomWorkflow | undefined): WorkflowField[] {
  return (workflow?.fields ?? []).filter((field) => FILTERABLE_FIELD_TYPES.includes(field.type));
}

/** The key of a `key:value` entry. */
const fieldKeyOf = (entry: string) => entry.slice(0, entry.indexOf(":"));

/** The values `fields` holds for one field key, in order. */
export function fieldValues(fields: readonly string[], key: string): string[] {
  return fields.filter((entry) => fieldKeyOf(entry) === key).map((entry) => entry.slice(key.length + 1));
}

/** `fields` with the values of `key` replaced by `values` (the key's entries move to the end), at most [MAX_FILTER_VALUES] entries. */
export function setFieldValues(fields: readonly string[], key: string, values: readonly string[]): string[] {
  return [...fields.filter((entry) => fieldKeyOf(entry) !== key), ...values.map((value) => `${key}:${value}`)].slice(0, MAX_FILTER_VALUES);
}

/** The thread list's request filters: what `listThreads` is asked for besides the page. */
export interface ThreadListFilters {
  closed?: boolean;
  sort: string;
  status?: string[];
  ownerId?: number[];
  field?: string[];
}

/** The owner filter's ids: `me` is the session's id (none without one), a number is itself. */
function ownerIds(owner: string | null, me: number | null): number[] {
  if (owner === null) return [];
  if (owner === "me") return me === null ? [] : [me];
  return [Number(owner)];
}

/**
 * The `field` entries the workflow can judge: the key is an active filterable field AND the value has the shape of its type (a user id for
 * USER, an option key for SELECT and MULTI_SELECT; the parser cannot tell, it keeps either shape). Anything else (a retired or renamed field,
 * `severity:7`, `lead:abc`) the server would answer with a 400.
 */
export function pruneFieldEntries(fields: readonly string[], workflow: RoomWorkflow): string[] {
  const types = new Map(filterableFields(workflow).map((field) => [field.key, field.type]));
  return fields.filter((entry) => {
    const type = types.get(fieldKeyOf(entry));
    if (type === undefined) return false;
    const value = entry.slice(entry.indexOf(":") + 1);
    return type === "USER" ? isUserId(value) : isKey(value);
  });
}

/**
 * The request for `params`. `owner=me` becomes the session's id `me` here (no id, no owner filter), and a `field` entry the workflow cannot
 * judge ([pruneFieldEntries]: a key that is not an active filterable field, a value of the wrong shape for its type) is DROPPED, never sent:
 * the server answers 400 for it. Field filters need the workflow, so while it is unread (`undefined`) the answer is `null`: nothing can be requested yet.
 */
export function toApiQuery(params: ThreadListParams, workflow: RoomWorkflow | undefined, me: number | null): ThreadListFilters | null {
  if (params.field.length > 0 && workflow === undefined) return null;
  const field = workflow === undefined ? [] : pruneFieldEntries(params.field, workflow);
  const owners = ownerIds(params.owner, me);
  return {
    closed: SCOPE_FILTER[params.scope],
    sort: ORDER_SORT[params.order],
    status: params.status.length > 0 ? params.status : undefined,
    ownerId: owners.length > 0 ? owners : undefined,
    field: field.length > 0 ? field : undefined,
  };
}

/** How many filters the panel holds: the statuses, the owner, and each field key with a value (the scope and the order live outside the panel). */
export function activeFilterCount(filters: Pick<ThreadListFilters, "status" | "ownerId" | "field">): number {
  const fieldKeys = new Set((filters.field ?? []).map(fieldKeyOf));
  return (filters.status ? 1 : 0) + (filters.ownerId ? 1 : 0) + fieldKeys.size;
}
