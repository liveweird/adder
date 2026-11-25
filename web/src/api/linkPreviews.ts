import { jsonRequest } from "./http";
import type { components } from "./schema";

export type LinkPreview = components["schemas"]["LinkPreviewResponse"];

export type MessageLinkSource = { roomId: number; messageId: number; editedAt: number | null };

export function previewMessageLink({ roomId, messageId }: MessageLinkSource, url: string, signal: AbortSignal): Promise<LinkPreview> {
  return jsonRequest<LinkPreview>(`/api/v1/rooms/${roomId}/messages/${messageId}/link-preview`, {
    method: "POST",
    body: JSON.stringify({ url }),
    signal,
  });
}
