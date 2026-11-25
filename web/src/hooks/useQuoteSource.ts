import { useQuery } from "@tanstack/react-query";
import { getMessage } from "../api/messages";
import type { QuoteDraft } from "./useThreadDrafts";
import { roomKeys } from "../utils/roomKeys";

/** Re-authorizes a quote draft whose source is outside the destination conversation before its excerpt is exposed. */
export function useQuoteSource(destinationRoomId: number, destinationThreadNumber: number, quote: QuoteDraft | null) {
  const foreign = quote !== null && (quote.roomId !== destinationRoomId || quote.threadNumber !== destinationThreadNumber);
  const query = useQuery({
    queryKey: roomKeys.quoteSource(quote?.roomId ?? 0, quote?.id ?? 0),
    queryFn: ({ signal }) => getMessage(quote!.roomId, quote!.id, signal),
    enabled: foreign && quote?.unavailable !== true,
    retry: false,
  });
  return { foreign, ...query };
}
