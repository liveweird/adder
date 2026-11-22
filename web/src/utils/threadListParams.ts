/**
 * The thread list's state as URL query parameters, the pure half (the hook is `hooks/useThreadListParams.ts`).
 *
 * Parsing drops junk (an unknown value reads as the default), and writing omits defaults, so a URL only ever spells what differs from
 * the plain list and a shared link means the same list for everyone. Keys that are not the list's pass through a write untouched; the two
 * view keys of the room (`focus`, `message`) are parsed and written here too, but are deliberately NOT list keys: the remembered query and
 * the in-room links carry the list's state only, never the focus view (a link back to the list must show it) or a message to jump to. To add a list key: put it in `ThreadListParams`, `DEFAULTS`, `LIST_KEYS`,
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
/** The most `tag` values one request may carry: the server accepts 50 raw values but folds them to at most 10 distinct. */
export const MAX_TAG_VALUES = 10;
/** The most `threadTag` (curated tag) values one request carries: the server's 20 distinct (the thread list and both searches). */
const MAX_THREAD_TAG_VALUES = 20;

export interface ThreadListParams {
  scope: Scope;
  order: Order;
  /** Any-of these status keys (empty = no filter). */
  status: string[];
  /** One owner: `me` (resolved to the session's id where the request is built, so a shared link means "my threads" for whoever opens it) or a user id. */
  owner: string | null;
  /** "Mentions me" (`mention=me`): only `me` is read; the request carries `mentionsMe=true`, which the server resolves to the caller (a direct mention, their team, everyone, the watchers they are in). */
  mention: boolean;
  /** "Watching" (`watching=me`): only `me` is read; the server resolves it to the caller, so a shared link means "my watched threads" for whoever opens it. */
  watching: boolean;
  /** "Unread" (`unread=me`): only `me` is read; the request carries `unread=true`, which the server resolves to the caller (a thread with something unread for them), so a shared link means "my unread threads" for whoever opens it. */
  unread: boolean;
  /** Any-of these hashtags (empty = no filter), as typed or as the room's tag list spells them; the server folds case and accents. */
  tag: string[];
  /** Any-of these CURATED tags (the registry's names, URL key `threadTag`; empty = no filter); a retired tag still finds the threads that kept it. */
  threadTag: string[];
  /** `key:value` pairs: any-of within a field key, all-of across keys. Judged against the room's workflow where the request is built. */
  field: string[];
  /** 1-based. */
  page: number;
}

export const DEFAULT_THREAD_LIST_PARAMS: ThreadListParams = { scope: "open", order: "activity", status: [], owner: null, mention: false, watching: false, unread: false, tag: [], threadTag: [], field: [], page: 1 };

/** Every query key the list owns, in the order a URL spells them. */
const LIST_KEYS = ["scope", "order", "status", "owner", "mention", "watching", "unread", "tag", "threadTag", "field", "page"] as const;

/** A positive integer of at most nine digits; anything else (`0`, `-1`, `2x`, `1e3`, a huge number) is junk. */
const PAGE = /^[1-9]\d{0,8}$/;

/** The server's key grammar (`KEY_GRAMMAR`, at most 40 characters): a status key, a field key, an option key. */
const KEY = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/;
const KEY_MAX_LENGTH = 40;
const isKey = (raw: string) => raw.length <= KEY_MAX_LENGTH && KEY.test(raw);
/** A user id as the server takes it: ASCII digits, no leading zero, within int32. */
const isUserId = (raw: string) => /^[1-9]\d{0,9}$/.test(raw) && Number(raw) <= 2_147_483_647;
/**
 * A tag value as the thread list takes it: 1 to 150 characters (code points), no `#`, no control character. It is a written tag (at most 50
 * characters) or a key the room's tag list returned (folded, up to 150, and it may hold punctuation: `hawai'i`, `1/2price`, `a 1/2`),
 * so the key grammar does not fit; the server folds whatever is typed.
 */
const TAG_MAX_LENGTH = 150;
const isTag = (raw: string) => [...raw].length <= TAG_MAX_LENGTH && raw !== "" && !/[#\p{Cc}]/u.test(raw);
/** A curated tag name as the registry spells it: 1 to 40 characters, a letter or digit first, then those, spaces and `. _ / + # : -` (the server's `TAG_GRAMMAR`). */
const THREAD_TAG = /^[\p{L}\p{N}][\p{L}\p{N} ._/+#:-]{0,39}$/u;
const isThreadTag = (raw: string) => THREAD_TAG.test(raw);
/** `key:value`: a field key, a colon, then an option key (SELECT, MULTI_SELECT) or a user id (USER). */
function isFieldEntry(raw: string): boolean {
  const colon = raw.indexOf(":");
  return colon > 0 && isKey(raw.slice(0, colon)) && (isKey(raw.slice(colon + 1)) || isUserId(raw.slice(colon + 1)));
}

/** The valid, distinct values of a repeated key, in URL order, at most `max` (by default [MAX_FILTER_VALUES]). */
function repeated(search: URLSearchParams, name: string, valid: (raw: string) => boolean, max = MAX_FILTER_VALUES): string[] {
  return [...new Set(search.getAll(name).filter(valid))].slice(0, max);
}

function oneOf<T extends string>(values: readonly T[], raw: string | null, fallback: T): T {
  return values.find((value) => value === raw) ?? fallback;
}

/** The list's state as the URL says it: junk and absent keys read as defaults. A repeated scalar key reads its first value; `status`, `tag` and `field` keep every valid value. */
export function parseThreadListParams(search: URLSearchParams): ThreadListParams {
  const page = search.get("page");
  const owner = search.get("owner");
  return {
    scope: oneOf(SCOPES, search.get("scope"), DEFAULT_THREAD_LIST_PARAMS.scope),
    order: oneOf(ORDERS, search.get("order"), DEFAULT_THREAD_LIST_PARAMS.order),
    status: repeated(search, "status", isKey),
    owner: owner !== null && (owner === "me" || isUserId(owner)) ? owner : DEFAULT_THREAD_LIST_PARAMS.owner,
    mention: search.get("mention") === "me",
    watching: search.get("watching") === "me",
    unread: search.get("unread") === "me",
    tag: repeated(search, "tag", isTag, MAX_TAG_VALUES),
    threadTag: threadTagValues(search),
    field: repeated(search, "field", isFieldEntry),
    page: page !== null && PAGE.test(page) ? Number(page) : DEFAULT_THREAD_LIST_PARAMS.page,
  };
}

/**
 * The `threadTag` values a URL spells (the thread list's and the search pages' key): the valid ones, lower-cased (the registry's names are lower case and the
 * server normalises a typed one, so a pasted `Data` is the same filter), distinct, in URL order, at most [MAX_THREAD_TAG_VALUES].
 */
export function threadTagValues(search: URLSearchParams): string[] {
  return [...new Set(repeated(search, "threadTag", isThreadTag, MAX_THREAD_TAG_VALUES).map((name) => name.toLowerCase()))];
}

/** Whether the URL spells any list key at all, valid or not (a bare URL is what the remembered query is restored onto). */
export function hasListKeys(search: URLSearchParams): boolean {
  return LIST_KEYS.some((key) => search.has(key));
}

/** The room view's focus switch: `focus=1` hides the thread list so the conversation takes the main area; anything else reads as off. */
export function parseFocus(search: URLSearchParams): boolean {
  return search.get("focus") === "1";
}

/** `base` with the focus switch on (`focus=1`) or off (the key removed); every other key survives. */
export function writeFocus(base: URLSearchParams, on: boolean): URLSearchParams {
  const out = new URLSearchParams(base);
  if (on) out.set("focus", "1");
  else out.delete("focus");
  return out;
}

/** A message id as the API carries it: a positive integer within JavaScript's safe range (at most 15 digits). */
const MESSAGE_ID = /^[1-9]\d{0,14}$/;

/** The message a thread link asks to jump to (`message=<id>`), or null when the key is absent or not a positive integer. */
export function parseMessageAnchor(search: URLSearchParams): number | null {
  const raw = search.get("message");
  return raw !== null && MESSAGE_ID.test(raw) ? Number(raw) : null;
}

/** The list keys that differ from the defaults, in URL order. */
function serialize(params: ThreadListParams): URLSearchParams {
  const out = new URLSearchParams();
  if (params.scope !== DEFAULT_THREAD_LIST_PARAMS.scope) out.set("scope", params.scope);
  if (params.order !== DEFAULT_THREAD_LIST_PARAMS.order) out.set("order", params.order);
  for (const status of params.status) out.append("status", status);
  if (params.owner !== DEFAULT_THREAD_LIST_PARAMS.owner) out.set("owner", String(params.owner));
  if (params.mention) out.set("mention", "me");
  if (params.watching) out.set("watching", "me");
  if (params.unread) out.set("unread", "me");
  for (const tag of params.tag) out.append("tag", tag);
  for (const tag of params.threadTag) out.append("threadTag", tag);
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
  mentionedId?: number[];
  /** The threads that address the CALLER, by name or through a group (`mentionsMe=true`); the server knows who the caller is. */
  mentionsMe?: boolean;
  /** The caller's own watched threads (`watching=true`); the server knows who the caller is. */
  watching?: boolean;
  /** The threads with something unread for the CALLER (`unread=true`); the server knows who the caller is. */
  unread?: boolean;
  tag?: string[];
  /** The curated tags (`threadTag`), any-of. */
  threadTag?: string[];
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
    mentionsMe: params.mention && me !== null ? true : undefined,
    watching: params.watching ? true : undefined,
    unread: params.unread ? true : undefined,
    tag: params.tag.length > 0 ? params.tag.slice(0, MAX_TAG_VALUES) : undefined,
    threadTag: params.threadTag.length > 0 ? params.threadTag.slice(0, MAX_THREAD_TAG_VALUES) : undefined,
    field: field.length > 0 ? field : undefined,
  };
}

/** How many filters the panel holds: the statuses, the owner, "mentions me", "watching", "unread", the hashtags (one dimension), the curated tags (one dimension), and each field key with a value (the scope and the order live outside the panel). */
export function activeFilterCount(filters: Pick<ThreadListFilters, "status" | "ownerId" | "mentionsMe" | "watching" | "unread" | "tag" | "threadTag" | "field">): number {
  const fieldKeys = new Set((filters.field ?? []).map(fieldKeyOf));
  return (filters.status ? 1 : 0) + (filters.ownerId ? 1 : 0) + (filters.mentionsMe ? 1 : 0) + (filters.watching ? 1 : 0) + (filters.unread ? 1 : 0) + (filters.tag ? 1 : 0) + (filters.threadTag ? 1 : 0) + fieldKeys.size;
}
