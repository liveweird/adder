import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Modal, Select, Stack, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listRooms, type RoomResponse } from "../api/rooms";
import { listThreads } from "../api/threads";
import type { MessageResponse } from "../api/messages";
import { useThreadDrafts, type QuoteDraft } from "../hooks/useThreadDrafts";
import { focusThreadPaneState, threadPath } from "../utils/roomLinks";
import { roomKeys } from "../utils/roomKeys";
import Pager from "./Pager";

const DESTINATION_LIMIT = 100;

/** Picks an open destination before carrying a source message there as a quote draft. The server remains the permission authority. */
export default function QuoteDestinationModal({
  source,
  currentRoom,
  onClose,
}: {
  source: MessageResponse;
  currentRoom: RoomResponse;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const drafts = useThreadDrafts();
  const [room, setRoom] = useState(currentRoom.role === null || currentRoom.archived ? null : { id: currentRoom.id, name: currentRoom.name });
  const [thread, setThread] = useState<{ number: number; title: string } | null>(null);
  const [roomPage, setRoomPage] = useState(1);
  const [threadPage, setThreadPage] = useState(1);
  const rooms = useQuery({
    queryKey: [...roomKeys.list, "quote-destinations", roomPage],
    queryFn: () => listRooms({ page: roomPage, pageSize: DESTINATION_LIMIT, member: true, archived: false, sort: "name" }),
    placeholderData: keepPreviousData,
  });
  const threads = useQuery({
    queryKey: [...roomKeys.threadList(room?.id ?? 0), "quote-destinations", threadPage],
    queryFn: () => listThreads(room!.id, { page: threadPage, pageSize: DESTINATION_LIMIT, closed: false, sort: "-lastActivityAt" }),
    enabled: room !== null,
    placeholderData: (previous, query) => query?.queryKey[2] === room?.id ? previous : undefined,
  });
  const roomOptions = rooms.data?.items.map(({ id, name }) => ({ value: String(id), label: name })) ?? [];
  const threadOptions = threads.data?.items.map(({ number, title }) => ({ value: String(number), label: `#${number} ${title}` })) ?? [];
  if (room && !roomOptions.some((option) => option.value === String(room.id))) roomOptions.unshift({ value: String(room.id), label: room.name });
  if (thread && !threadOptions.some((option) => option.value === String(thread.number))) {
    threadOptions.unshift({ value: String(thread.number), label: `#${thread.number} ${thread.title}` });
  }

  function carryQuote() {
    if (room === null || thread === null) return;
    const { id, authorId, authorName, body, mentions, groupMentions } = source;
    const quote: QuoteDraft = {
      id,
      authorId,
      authorName,
      body,
      mentions,
      groupMentions,
      roomId: currentRoom.id,
      threadNumber: source.threadNumber,
    };
    drafts.setQuote(room.id, thread.number, quote);
    onClose();
    void navigate(threadPath(room.id, thread.number), { state: focusThreadPaneState });
  }

  return (
    <Modal opened onClose={onClose} title={t("threads.quote.destinationTitle")} centered closeButtonProps={{ "aria-label": t("common.action.close") }}>
      <Stack gap="md">
        <Text size="sm" c="dimmed">{t("threads.quote.destinationDescription")}</Text>
        {(rooms.isError || threads.isError) && <Alert color="red">{t("threads.quote.destinationError")}</Alert>}
        <Select
          label={t("threads.quote.destinationRoom")}
          data={roomOptions}
          value={room ? String(room.id) : null}
          searchable
          // Keep a focused picker usable while a live hint refreshes the current page.
          disabled={rooms.isPending || rooms.isPlaceholderData}
          onChange={(value, option) => {
            setRoom(option === null ? null : { id: Number(value), name: option.label });
            setThread(null);
            setThreadPage(1);
          }}
        />
        <DestinationPages label={t("threads.quote.destinationRoom")} page={roomPage} total={rooms.data?.total} onChange={setRoomPage} />
        <Select
          label={t("threads.quote.destinationThread")}
          placeholder={threads.isLoading ? t("common.loading") : undefined}
          data={threadOptions}
          value={thread ? String(thread.number) : null}
          searchable
          disabled={room === null || threads.isPending || threads.isPlaceholderData}
          nothingFoundMessage={t("threads.quote.destinationNone")}
          onChange={(value) => setThread(threads.data?.items.find((item) => String(item.number) === value) ?? null)}
        />
        <DestinationPages label={t("threads.quote.destinationThread")} page={threadPage} total={threads.data?.total} onChange={setThreadPage} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>{t("common.action.cancel")}</Button>
          <Button onClick={carryQuote} disabled={room === null || thread === null || threads.isPlaceholderData}>{t("threads.quote.destinationContinue")}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function DestinationPages({ label, page, total, onChange }: { label: string; page: number; total: number | undefined; onChange: (page: number) => void }) {
  const pages = Math.ceil((total ?? 0) / DESTINATION_LIMIT);
  return pages > 1 ? <Pager role="navigation" aria-label={label} page={page} pages={pages} onChange={onChange} size="sm" /> : null;
}
