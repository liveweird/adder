import type { QueryClient } from "@tanstack/react-query";
import { previewMessageLink, type LinkPreview, type MessageLinkSource } from "../api/linkPreviews";
import { getSessionSnapshot } from "../api/session";

const PREVIEW_KEY = ["message-link-preview"] as const;
const LIFETIME_MS = 5 * 60 * 1_000;
const MAX_PREVIEWS = 32;

export function previewSessionIdentity(): string | null {
  return getSessionSnapshot().identity;
}

const previewKey = ({ roomId, messageId, editedAt }: MessageLinkSource, url: string, identity: string) => (
  [...PREVIEW_KEY, roomId, messageId, editedAt, identity, url] as const
);

export function clearRoomLinkPreviews(client: QueryClient, roomId: number): void {
  client.removeQueries({ queryKey: [...PREVIEW_KEY, roomId] });
}

export function cachedLinkPreview(client: QueryClient, source: MessageLinkSource, url: string, identity: string): LinkPreview | undefined {
  const state = client.getQueryState<LinkPreview>(previewKey(source, url, identity));
  return state && Date.now() - state.dataUpdatedAt < LIFETIME_MS ? state.data : undefined;
}

export async function loadLinkPreview(client: QueryClient, source: MessageLinkSource, url: string, identity: string): Promise<LinkPreview> {
  const queryKey = previewKey(source, url, identity);
  const result = await client.fetchQuery({
    queryKey,
    queryFn: ({ signal }) => previewMessageLink(source, url, signal),
    staleTime: LIFETIME_MS,
    gcTime: LIFETIME_MS,
    retry: false,
    networkMode: "always",
  });
  const cache = client.getQueryCache();
  const current = cache.find({ queryKey, exact: true });
  const otherResults = cache.findAll({ queryKey: PREVIEW_KEY, type: "inactive" })
    .filter((entry) => entry.state.data !== undefined && entry !== current)
    .sort((left, right) => right.state.dataUpdatedAt - left.state.dataUpdatedAt);
  otherResults.slice(MAX_PREVIEWS - 1).forEach((entry) => client.removeQueries({ queryKey: entry.queryKey, exact: true }));
  return result;
}
