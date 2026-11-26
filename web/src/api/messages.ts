// Messages API — the conversation of one thread (`/api/v1/rooms/{id}/threads/{number}/messages`) and single messages
// (`/api/v1/rooms/{id}/messages/{messageId}`; API-RES-006 bans three path parameters). The room's guards apply unchanged (a non-member and an
// unknown room get the same 404); speech is for members only (an ADMIN who is not a member gets a 403), a closed thread answers 409
// `thread-closed` and an archived room 409 `room-archived`. Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type MessagePage = paths["/api/v1/rooms/{id}/threads/{number}/messages"]["get"]["responses"]["200"]["content"]["application/json"];
/** One message. A deleted one is a tombstone: `body` is null, `deleted` is true, `editedAt` is null and `deletedByModerator` says whose hand removed it. */
export type MessageResponse = MessagePage["items"][number];
export type MessageContext = paths["/api/v1/rooms/{id}/threads/{number}/messages/context"]["get"]["responses"]["200"]["content"]["application/json"];
/** The quoted message as a message answers it (1.3): live, as it reads NOW; `deleted` has no text. Not the draft a composer holds (`useThreadDrafts`' `QuoteDraft`). */
export type MessageQuoteSummary = NonNullable<MessageResponse["quote"]>;
export type MessageBody = paths["/api/v1/rooms/{id}/threads/{number}/messages"]["post"]["requestBody"]["content"]["application/json"];
export type MessageEditBody = paths["/api/v1/rooms/{id}/messages/{messageId}"]["put"]["requestBody"]["content"]["application/json"];
export type MessageRevisionPage = paths["/api/v1/rooms/{id}/messages/{messageId}/revisions"]["get"]["responses"]["200"]["content"]["application/json"];

/** One live source message, re-read for a cross-room quote draft and hidden by the server as 404 when access is gone. */
export function getMessage(roomId: number, messageId: number, signal?: AbortSignal): Promise<MessageResponse> {
  return jsonRequest<MessageResponse>(`/api/v1/rooms/${roomId}/messages/${messageId}`, { signal });
}

/**
 * One page of a thread's messages, NEWEST first (`sort=-id`), tombstones included. `idLt` keeps only messages older than that id (keyset
 * paging inside the standard envelope): `total` counts after the filter, so `total > items.length` means older messages exist.
 */
export async function listMessages(roomId: number, threadNumber: number, q: { idLt?: number; pageSize: number }): Promise<MessagePage> {
  const params = buildQuery({ page: 1, pageSize: q.pageSize, sort: "-id", "id[lt]": q.idLt });
  return jsonRequest<MessagePage>(`/api/v1/rooms/${roomId}/threads/${threadNumber}/messages?${params}`);
}

/** A bounded, chronological window of messages around an anchor, including tombstones. */
export function getMessageContext(roomId: number, threadNumber: number, messageId: number, signal?: AbortSignal): Promise<MessageContext> {
  const params = buildQuery({ messageId });
  return jsonRequest<MessageContext>(`/api/v1/rooms/${roomId}/threads/${threadNumber}/messages/context?${params}`, { signal });
}

/**
 * Members only; 429 on the per-user `chat-post` bucket. The body is already normalised (`normalizeBody`). `quotedMessageId` makes the message
 * quote any live message the caller can currently read; it is sent only when there is one, and a quote that cannot be made is a typed 400
 * (`PROBLEM_TYPES.quoteUnavailable`).
 */
export async function postMessage(
  roomId: number, threadNumber: number, body: string, quotedMessageId?: number, mutationId?: string,
): Promise<MessageResponse> {
  const payload: MessageBody = quotedMessageId === undefined ? { body } : { body, quotedMessageId };
  return jsonRequest<MessageResponse>(`/api/v1/rooms/${roomId}/threads/${threadNumber}/messages`, {
    method: "POST",
    body: JSON.stringify(payload),
    ...(mutationId === undefined ? {} : { headers: { "X-Mutation-Id": mutationId } }),
  });
}

export type ReactionSummary = MessageResponse["reactions"][number];
export type ReactorPage = paths["/api/v1/rooms/{id}/messages/{messageId}/reactors"]["get"]["responses"]["200"]["content"]["application/json"];
type ReactionBody = paths["/api/v1/rooms/{id}/messages/{messageId}/reaction"]["put"]["requestBody"]["content"]["application/json"];

/**
 * Sets whether the caller has this one emoji on a message (`reacted`), idempotent: 204 whether or not it changed. The emoji travels in the body,
 * never in a path. Members only; 409 `thread-closed`, `room-archived` or `reaction-limit` (20 distinct emoji on a message, 5 of the caller's).
 */
export async function setMessageReaction(roomId: number, messageId: number, emoji: string, reacted: boolean): Promise<void> {
  const payload: ReactionBody = { emoji, reacted };
  return voidRequest(`/api/v1/rooms/${roomId}/messages/${messageId}/reaction`, { method: "PUT", body: JSON.stringify(payload) });
}

/** Full reactor roster behind one message summary. The opaque key keeps the chosen emoji out of the URL. */
export async function listMessageReactors(roomId: number, messageId: number, reactionKey: string, page: number, pageSize: number): Promise<ReactorPage> {
  const params = buildQuery({ reactionKey, page, pageSize, sort: "createdAt" });
  return jsonRequest<ReactorPage>(`/api/v1/rooms/${roomId}/messages/${messageId}/reactors?${params}`);
}

/** The author's own message, while a member; an unchanged body is a server-side no-op (204). */
export async function editMessage(roomId: number, messageId: number, body: string): Promise<void> {
  const payload: MessageEditBody = { body };
  return voidRequest(`/api/v1/rooms/${roomId}/messages/${messageId}`, { method: "PUT", body: JSON.stringify(payload) });
}

/** Earlier bodies for one edited message, newest first. The server marks this sensitive response `no-store`. */
export async function listMessageRevisions(
  roomId: number, messageId: number, page: number, pageSize: number, signal: AbortSignal,
): Promise<MessageRevisionPage> {
  const params = buildQuery({ page, pageSize, sort: "-id" });
  return jsonRequest<MessageRevisionPage>(`/api/v1/rooms/${roomId}/messages/${messageId}/revisions?${params}`, { signal });
}

/** The author's own message, or any message for a room OWNER or an ADMIN (a moderator removal, shown as such in the tombstone). */
export async function deleteMessage(roomId: number, messageId: number): Promise<void> {
  return voidRequest(`/api/v1/rooms/${roomId}/messages/${messageId}`, { method: "DELETE" });
}
