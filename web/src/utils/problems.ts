import { ApiError } from "../api/http";

/** The RFC 7807 problem `type` URIs the server answers 409, and the typed 429s (`room-busy`, `search-timeout`, `search-busy`), with (`openapi/documentation.yaml`, `chat-model.md`). */
export const PROBLEM_TYPES = {
  revisionConflict: "urn:adder:problem:revision-conflict",
  lastOwner: "urn:adder:problem:last-owner",
  roomArchived: "urn:adder:problem:room-archived",
  threadClosed: "urn:adder:problem:thread-closed",
  statusInUse: "urn:adder:problem:status-in-use",
  fieldTypeImmutable: "urn:adder:problem:field-type-immutable",
  roomBusy: "urn:adder:problem:room-busy",
  /** A search outran its deadline: narrow it. */
  searchTimeout: "urn:adder:problem:search-timeout",
  /** The server already runs its maximum of searches: nothing to narrow, retry shortly. */
  searchBusy: "urn:adder:problem:search-busy",
} as const;

export type ProblemType = (typeof PROBLEM_TYPES)[keyof typeof PROBLEM_TYPES];

/** True when `err` is an `ApiError` whose problem body carries exactly this `type`. */
export function isProblem(err: unknown, type: ProblemType): err is ApiError {
  return err instanceof ApiError && err.type === type;
}
