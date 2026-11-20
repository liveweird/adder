import { ApiError } from "../api/http";

/** The RFC 7807 problem `type` URIs the server answers 409, `pin-limit`, `reaction-limit`, and the typed 429s (`room-busy`, `search-timeout`, `search-busy`, `read-timeout`, `read-busy`), with (`openapi/documentation.yaml`, `chat-model.md`). */
export const PROBLEM_TYPES = {
  revisionConflict: "urn:adder:problem:revision-conflict",
  lastOwner: "urn:adder:problem:last-owner",
  roomArchived: "urn:adder:problem:room-archived",
  threadClosed: "urn:adder:problem:thread-closed",
  statusInUse: "urn:adder:problem:status-in-use",
  fieldTypeImmutable: "urn:adder:problem:field-type-immutable",
  roomBusy: "urn:adder:problem:room-busy",
  /** The message already holds 20 distinct emoji, or the caller already chose 5 on it. */
  reactionLimit: "urn:adder:problem:reaction-limit",
  /** The room already holds its 50 pins: unpin one first. */
  pinLimit: "urn:adder:problem:pin-limit",
  /** A search outran its deadline: narrow it. */
  searchTimeout: "urn:adder:problem:search-timeout",
  /** The server already runs its maximum of searches: nothing to narrow, retry shortly. */
  searchBusy: "urn:adder:problem:search-busy",
  /** A filtered thread list or a room's tag list outran its deadline: narrow the filter or retry. */
  readTimeout: "urn:adder:problem:read-timeout",
  /** The caller or the server already runs its maximum of list reads: nothing to narrow, retry shortly. */
  readBusy: "urn:adder:problem:read-busy",
} as const;

export type ProblemType = (typeof PROBLEM_TYPES)[keyof typeof PROBLEM_TYPES];

/** True when `err` is an `ApiError` whose problem body carries exactly this `type`. */
export function isProblem(err: unknown, type: ProblemType): err is ApiError {
  return err instanceof ApiError && err.type === type;
}
