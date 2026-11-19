import { useState } from "react";
import { useTranslation } from "react-i18next";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listThreadEvents } from "../api/threads";
import { describeEvent, eventLines } from "../utils/threadEvents";
import EventTimeline from "./EventTimeline";
import { roomKeys } from "../utils/roomKeys";

/** Ten a page: the history sits in a narrow side panel, newest first. */
const PAGE_SIZE = 10;

/**
 * A thread's change history (Toadie's `CatalogFileHistory`): the server's structured events rendered in the viewer's language, newest first
 * (server-ordered, never re-sorted here), ten to a page. Under `["rooms", "threads", roomId, "events", number, page]`, so any write that
 * refreshes the thread's queries or the whole `["rooms"]` prefix refreshes the trail too. The actor's name carries a note when the account has
 * since been deleted (the recorded name stays).
 */
export default function ThreadHistory({ roomId, number }: { roomId: number; number: number }) {
  const { t } = useTranslation();
  const [page, setPage] = useState(1);
  const { data, isLoading, isError, error, isPlaceholderData, refetch } = useQuery({
    queryKey: [...roomKeys.threadEvents(roomId, number), page],
    queryFn: () => listThreadEvents(roomId, number, { page, pageSize: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });

  // The last total a page told us: a page that fails to load has none, and the pager must stay so the reader can go back.
  const [knownTotal, setKnownTotal] = useState(0);
  if (data && data.total !== knownTotal) setKnownTotal(data.total);

  return (
    <EventTimeline
      items={data?.items.map((event) => ({
        key: event.id,
        title: describeEvent(event, t),
        lines: eventLines(event, t),
        who: event.userDeleted ? t("threads.history.userDeleted", { name: event.userName }) : event.userName,
        when: event.createdAt,
      }))}
      isLoading={isLoading}
      isError={isError}
      error={error}
      isPlaceholder={isPlaceholderData}
      emptyMessage={t("threads.history.empty")}
      page={page}
      pageSize={PAGE_SIZE}
      total={data?.total ?? knownTotal}
      onPageChange={setPage}
      onRetry={() => void refetch()}
    />
  );
}
