import { useState } from "react";
import { Anchor, Button, Modal, Stack, Text } from "@mantine/core";
import { IconGitMerge } from "@tabler/icons-react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Link, useLocation } from "react-router-dom";
import { listMergedThreads } from "../api/threadLifecycle";
import { roomKeys } from "../utils/roomKeys";
import { threadPath, withListParams } from "../utils/roomLinks";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import PaginationBar from "./PaginationBar";

const PAGE_SIZE = 20;

/** Contextual, paged access from a canonical thread to its read-only merged histories. */
export default function MergedThreadSources({ roomId, targetNumber, count }: { roomId: number; targetNumber: number; count: number }) {
  const { t } = useTranslation();
  const location = useLocation();
  const [opened, setOpened] = useState(false);
  const [page, setPage] = useState(1);
  const query = useQuery({
    queryKey: [...roomKeys.threadList(roomId), "merged", targetNumber, page],
    queryFn: () => listMergedThreads(roomId, targetNumber, page, PAGE_SIZE),
    enabled: opened,
    placeholderData: keepPreviousData,
  });
  if (count <= 0) return null;

  return (
    <>
      <Button variant="subtle" size="compact-sm" leftSection={<IconGitMerge size={16} aria-hidden />} onClick={() => setOpened(true)}>
        {t("threadLifecycle.sources.open", { count })}
      </Button>
      <Modal
        opened={opened}
        onClose={() => {
          setOpened(false);
          setPage(1);
        }}
        title={t("threadLifecycle.sources.title")}
        closeButtonProps={{ "aria-label": t("common.action.close") }}
        centered
      >
        <Stack gap="md" aria-busy={query.isPlaceholderData || undefined}>
          <Text size="sm" c="dimmed">{t("threadLifecycle.sources.description")}</Text>
          {query.isLoading && <LoadingBlock />}
          {query.isError && <ErrorAlert error={query.error} title={t("threadLifecycle.sources.loadFailed")} />}
          {query.data?.total === 0 && <Text size="sm" c="dimmed">{t("threadLifecycle.sources.empty")}</Text>}
          {query.data && query.data.items.length > 0 && (
            <Stack component="ul" gap="xs" m={0} p={0} style={{ listStyle: "none" }}>
              {query.data.items.map((source) => (
                <Text component="li" key={source.number} size="sm">
                  <Anchor component={Link} to={withListParams(threadPath(roomId, source.number), location.search)}>
                    {`#${source.number} ${source.title}`}
                  </Anchor>
                </Text>
              ))}
            </Stack>
          )}
          {query.data && query.data.total > PAGE_SIZE && (
            <PaginationBar total={query.data.total} page={page} pageSize={PAGE_SIZE} onPageChange={setPage} />
          )}
        </Stack>
      </Modal>
    </>
  );
}
