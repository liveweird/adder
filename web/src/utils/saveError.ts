import type { ParseKeys, TFunction } from "i18next";
import { ApiError, isTimeoutError } from "../api/http";
import i18n from "../i18n";
import { PROBLEM_TYPES, isProblem } from "./problems";

export interface SaveErrorKeys {
  /** 401 — omit to route it through the generic fallbacks (the login page's wrong-credentials). */
  unauthorized?: ParseKeys;
  /** 429 — omit to route it through the generic fallbacks (the login page's account lockout). */
  tooManyRequests?: ParseKeys;
  /**
   * 429 — the SHARED "too fast, try again in N seconds" words (`common.error.tooFast`, from the response's `Retry-After`; "in a moment"
   * without one). The room writes on the per-user buckets use it; wins over `tooManyRequests`.
   */
  retryAfter?: boolean;
  /** 403. Omit only when the page routes a 403 through the generic fallbacks. */
  forbidden?: ParseKeys;
  /** 404 — omit to route it through the generic fallbacks. */
  notFound?: ParseKeys;
  /** 409 — omit to route it through the generic fallbacks. */
  conflict?: ParseKeys;
  /** 400 — omit to route it through the generic fallbacks. */
  invalid?: ParseKeys;
  /** 503 — omit to route it through the generic fallbacks (mail-less deployments answer 503). */
  unavailable?: ParseKeys;
  /** Any otherwise-unmatched ApiError status; interpolates {{status}}. */
  failedStatus?: ParseKeys;
  /** Generic + network fallback. */
  failed: ParseKeys;
}

/** A bucket's 429 in the shared words: whole seconds rounded up from the `Retry-After` (at least one), else "in a moment". */
function tooFastMessage(err: ApiError, t: TFunction): string {
  if (err.retryAfterMs === null || err.retryAfterMs <= 0) return t("common.error.tooFastSoon");
  return t("common.error.tooFast", { count: Math.ceil(err.retryAfterMs / 1000) });
}

/**
 * Maps a mutation failure to the page's fixed vocabulary — never render error.message. A `room-busy` 429 (the room's write lock stayed busy,
 * nothing was written) is NOT a rate-limit bucket and has its own words everywhere, whatever the page's `tooManyRequests` says.
 */
export function saveErrorMessage(err: unknown, t: TFunction, keys: SaveErrorKeys): string {
  if (isProblem(err, PROBLEM_TYPES.roomBusy)) return t("common.error.roomBusy");
  if (err instanceof ApiError) {
    if (err.status === 401 && keys.unauthorized) return t(keys.unauthorized);
    if (err.status === 429 && keys.retryAfter) return tooFastMessage(err, t);
    if (err.status === 429 && keys.tooManyRequests) return t(keys.tooManyRequests);
    if (err.status === 403 && keys.forbidden) return t(keys.forbidden);
    if (err.status === 404 && keys.notFound) return t(keys.notFound);
    if (err.status === 409 && keys.conflict) return t(keys.conflict);
    if (err.status === 400 && keys.invalid) return t(keys.invalid);
    if (err.status === 503 && keys.unavailable) return t(keys.unavailable);
    if (keys.failedStatus) return t(keys.failedStatus, { status: err.status });
  }
  if (isTimeoutError(err)) return t("common.error.timeout");
  return t(keys.failed);
}

/** The user-save 409 disambiguator: last-admin demotion vs an email already in use. */
export function isLastAdminConflict(err: unknown): boolean {
  return err instanceof ApiError && err.status === 409 &&
    (err.detail?.toLowerCase().includes("administrator") ?? false);
}

/** The list-load counterpart: status-tagged for ApiErrors, generic otherwise. */
export function loadErrorMessage(err: unknown, t: TFunction): string {
  if (isProblem(err, PROBLEM_TYPES.roomBusy)) return t("common.error.roomBusy");
  // The two typed 429s of the list reads (`read-timeout`: narrow the filter; `read-busy`: retry shortly), apart from the bucket's.
  if (isProblem(err, PROBLEM_TYPES.readTimeout)) return t("common.error.readTimeout");
  if (isProblem(err, PROBLEM_TYPES.readBusy)) return t("common.error.readBusy");
  // 429: the per-user rate-limit bucket (security.md) — say what to do, not just the status.
  if (err instanceof ApiError && err.status === 429) return t("common.error.tooManyRequests");
  if (err instanceof ApiError) return t("common.error.loadFailedStatus", { status: err.status });
  if (isTimeoutError(err)) return t("common.error.timeout");
  return t("common.error.network");
}

/**
 * A 400's own sentence (the server names the field or the rule) appended to the UI's words — in English only: the server's `detail` is English
 * prose, and appending it to a Polish message would mix the two languages in one sentence. Other languages keep the translated words alone.
 */
export function withServerDetail(message: string, err: unknown): string {
  return i18n.resolvedLanguage === "en" && err instanceof ApiError && err.status === 400 && err.detail ? `${message} (${err.detail})` : message;
}
