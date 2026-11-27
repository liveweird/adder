import type { QueryClient, QueryKey } from "@tanstack/react-query";
import type { LiveServerFrame } from "./liveProtocol";

type QuoteLocation = { roomId: number; threadNumber: number; messageId: number };

function isQuoteLocation(value: unknown): value is QuoteLocation {
  if (typeof value !== "object" || value === null) return false;
  const quote = value as Partial<QuoteLocation>;
  return typeof quote.roomId === "number" && typeof quote.threadNumber === "number" && typeof quote.messageId === "number";
}

function containsSource(value: unknown, matches: (quote: QuoteLocation) => boolean, seen = new Set<object>()): boolean {
  if (typeof value !== "object" || value === null || seen.has(value)) return false;
  seen.add(value);
  if ("quote" in value && isQuoteLocation(value.quote) && matches(value.quote)) return true;
  if (Array.isArray(value)) return value.some((item) => containsSource(item, matches, seen));
  return Object.values(value).some((item) => containsSource(item, matches, seen));
}

function isMessageOrPinKey(key: QueryKey): boolean {
  return key[0] === "rooms" && (key.includes("messages") || key.includes("pins"));
}

/** Cached destination conversations/pins whose rendered quote depends on the source named by this frame. */
export function quoteDependencyKeys(queryClient: QueryClient, frame: LiveServerFrame): QueryKey[] {
  const source = frame.type === "revoked"
    ? { roomId: frame.roomId }
    : frame.type === "event" && ["message.edited", "message.deleted", "thread.deleted", "room.deleted"].includes(frame.kind)
      ? { roomId: frame.roomId, threadNumber: frame.threadNumber, messageId: frame.messageId }
      : null;
  if (source === null) return [];
  const matches = (quote: QuoteLocation) => {
    if (quote.roomId !== source.roomId) return false;
    if (frame.type !== "event") return true;
    if ((frame.kind === "message.edited" || frame.kind === "message.deleted") && source.messageId !== undefined) {
      return quote.messageId === source.messageId;
    }
    // A lifecycle hint names the canonical thread only; deleted merged children may hold any other number in the source room.
    if (frame.kind === "thread.deleted") return true;
    return true;
  };
  return queryClient.getQueryCache().getAll()
    .filter((query) => isMessageOrPinKey(query.queryKey) && (
      containsSource(query.state.data, matches) ||
      (frame.type === "revoked" && query.state.fetchStatus === "fetching" && query.queryKey[2] !== frame.roomId)
    ))
    .map((query) => query.queryKey);
}
