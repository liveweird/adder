import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Modal, Stack, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listMessageReactors, type ReactionSummary } from "../api/messages";
import { roomKeys } from "../utils/roomKeys";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import PaginationBar from "./PaginationBar";
import PersonLink from "./PersonLink";

const PAGE_SIZE = 20;

/** Keyboard- and touch-accessible full roster for one reaction. */
export default function MessageReactionRoster({
  roomId,
  threadNumber,
  messageId,
  reaction,
  opened,
  onClose,
}: {
  roomId: number;
  threadNumber: number;
  messageId: number;
  reaction: ReactionSummary;
  opened: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [page, setPage] = useState(1);
  const query = useQuery({
    queryKey: [...roomKeys.messages(roomId, threadNumber), "reactors", messageId, reaction.key, page],
    queryFn: () => listMessageReactors(roomId, messageId, reaction.key, page, PAGE_SIZE),
    enabled: opened,
    placeholderData: keepPreviousData,
  });

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={t("threads.reaction.peopleTitle", { emoji: reaction.emoji })}
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      centered
      size="sm"
    >
      {query.isLoading && <LoadingBlock py="sm" />}
      {query.isError && <ErrorAlert error={query.error} />}
      {query.data && (
        <Stack gap="md" aria-busy={query.isPlaceholderData} style={query.isPlaceholderData ? { opacity: 0.6 } : undefined}>
          <Stack component="ul" gap="xs" m={0} p={0} style={{ listStyle: "none" }}>
            {query.data.items.map((person) => (
              <Text component="li" key={person.userId} size="sm">
                <PersonLink id={person.userId} name={person.name} />
              </Text>
            ))}
          </Stack>
          <PaginationBar total={query.data.total} page={page} pageSize={PAGE_SIZE} onPageChange={setPage} />
        </Stack>
      )}
    </Modal>
  );
}
