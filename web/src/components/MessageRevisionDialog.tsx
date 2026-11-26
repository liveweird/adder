import { useEffect, useState } from "react";
import { Box, Modal, Paper, Stack, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { listMessageRevisions } from "../api/messages";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { clearMessageRevisions } from "../utils/messageRevisionCache";
import { isAccessRefusal } from "../utils/roomAccess";
import { roomKeys } from "../utils/roomKeys";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import PaginationBar from "./PaginationBar";

const PAGE_SIZE = 20;

/** Sensitive earlier bodies, loaded only while the accessible dialog is open and never rendered as Markdown. */
export default function MessageRevisionDialog({
  roomId,
  threadNumber,
  messageId,
  opened,
  onClose,
}: {
  roomId: number;
  threadNumber: number;
  messageId: number;
  opened: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const zone = browserTimeZone();
  const query = useQuery({
    queryKey: [...roomKeys.messageRevisions(roomId, threadNumber, messageId), page],
    queryFn: ({ signal }) => listMessageRevisions(roomId, messageId, page, PAGE_SIZE, signal),
    enabled: opened,
    gcTime: 0,
  });
  const history = query.isError ? undefined : query.data;

  useEffect(() => () => {
    clearMessageRevisions(queryClient, roomId, threadNumber, messageId);
  }, [messageId, queryClient, roomId, threadNumber]);
  useEffect(() => {
    if (!opened || !query.isError || !isAccessRefusal(query.error)) return;
    clearMessageRevisions(queryClient, roomId, threadNumber, messageId);
    onClose();
  }, [messageId, onClose, opened, query.error, query.isError, queryClient, roomId, threadNumber]);

  function close() {
    setPage(1);
    clearMessageRevisions(queryClient, roomId, threadNumber, messageId);
    onClose();
  }

  return (
    <Modal
      opened={opened}
      onClose={close}
      title={t("threads.message.history.title")}
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      centered
      size="lg"
      returnFocus
      trapFocus
    >
      <Stack gap="md" aria-busy={query.isFetching}>
        {query.isLoading && <LoadingBlock py="sm" />}
        {query.isError && <ErrorAlert error={query.error} title={t("threads.message.history.loadFailed")} />}
        {history?.total === 0 && <Text size="sm" c="dimmed">{t("threads.message.history.empty")}</Text>}
        {history?.items.map((revision) => (
          <Paper key={revision.id} withBorder p="md" radius="sm">
            <Stack gap="xs">
              <Text size="xs" c="dimmed">
                {t("threads.message.history.version", {
                  from: formatDateTime(revision.createdAt, zone),
                  until: formatDateTime(revision.supersededAt, zone),
                })}
              </Text>
              <Box component="pre" m={0} fz="sm" style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere", fontFamily: "inherit" }}>
                {revision.body}
              </Box>
            </Stack>
          </Paper>
        ))}
        {history && history.total > PAGE_SIZE && (
          <PaginationBar total={history.total} page={page} pageSize={PAGE_SIZE} onPageChange={setPage} />
        )}
      </Stack>
    </Modal>
  );
}
