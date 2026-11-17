import { ApiError } from "../api/http";

/** The RFC 7807 problem `type` URIs the server answers 409 with (`openapi/documentation.yaml`, `chat-model.md`). */
export const PROBLEM_TYPES = {
  revisionConflict: "urn:adder:problem:revision-conflict",
  lastOwner: "urn:adder:problem:last-owner",
  roomArchived: "urn:adder:problem:room-archived",
  statusInUse: "urn:adder:problem:status-in-use",
  fieldTypeImmutable: "urn:adder:problem:field-type-immutable",
} as const;

export type ProblemType = (typeof PROBLEM_TYPES)[keyof typeof PROBLEM_TYPES];

/** True when `err` is an `ApiError` whose problem body carries exactly this `type`. */
export function isProblem(err: unknown, type: ProblemType): err is ApiError {
  return err instanceof ApiError && err.type === type;
}
