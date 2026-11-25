import { useState } from "react";
import { List, Modal, Stack, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { listThreadWatchers } from "../api/threads";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import PaginationBar from "./PaginationBar";
import PersonLink from "./PersonLink";
import { roomKeys } from "../utils/roomKeys";

const PAGE_SIZE = 20;

/** A member-visible, paged roster. The modal's trigger owns its count, so this component only reads while open. */
export default function ThreadWatcherModal({ roomId, number, opened, onClose }: { roomId: number; number: number; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const [page, setPage] = useState(1);
  const query = useQuery({
    queryKey: [...roomKeys.threadWatchers(roomId, number), page],
    queryFn: () => listThreadWatchers(roomId, number, { page, pageSize: PAGE_SIZE, sort: "name" }),
    enabled: opened,
  });

  return (
    <Modal
      opened={opened}
      onClose={() => {
        setPage(1);
        onClose();
      }}
      title={t("threads.watch.peopleTitle")}
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      centered
    >
      <Stack gap="md">
        {query.isLoading && <LoadingBlock />}
        {query.isError && <ErrorAlert error={query.error} title={t("threads.watch.peopleLoadFailed")} />}
        {query.data?.total === 0 && <Text c="dimmed">{t("threads.watch.peopleEmpty")}</Text>}
        {query.data && query.data.items.length > 0 && (
          <>
            <List spacing="sm" aria-label={t("threads.watch.peopleTitle")}>
              {query.data.items.map((watcher) => (
                <List.Item key={watcher.userId}>
                  <PersonLink id={watcher.userId} name={watcher.name} />
                </List.Item>
              ))}
            </List>
            {query.data.total > PAGE_SIZE && (
              <PaginationBar total={query.data.total} page={page} pageSize={PAGE_SIZE} onPageChange={setPage} />
            )}
          </>
        )}
      </Stack>
    </Modal>
  );
}
