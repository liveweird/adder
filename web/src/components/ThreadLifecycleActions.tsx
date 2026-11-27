import { useState } from "react";
import { ActionIcon, Alert, Button, Group, List, Menu, Modal, NumberInput, Paper, Stack, Text, Tooltip } from "@mantine/core";
import { IconDotsVertical, IconGitMerge, IconTrash } from "@tabler/icons-react";
import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { useAdmin } from "../auth";
import { API_BASE } from "../api/http";
import { deleteThread, getLifecycleThread, mergeThread, type LifecycleThread } from "../api/threadLifecycle";
import type { RoomResponse } from "../api/rooms";
import { getSessionSnapshot, getUserId, isSameSessionIdentity } from "../api/session";
import { roomKeys } from "../utils/roomKeys";
import { threadLifecycleKeys } from "../utils/threadLifecycleCache";
import { beginThreadDeleteCommit } from "../utils/threadDeleteCommit";
import { beginMergeEcho, canCorrelateReply } from "../utils/liveReplyEcho";
import { activeQueriesAreFresh } from "../utils/queryRefresh";
import { isThreadSteward } from "../utils/threadAccess";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import FormErrorAlert from "./FormErrorAlert";

export type { LifecycleThread } from "../api/threadLifecycle";

const MAX_ID = 2_147_483_647;

function numberValue(value: string | number): number | null {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isInteger(number) && number >= 1 && number <= MAX_ID ? number : null;
}

async function refreshMergedThread(
  queryClient: QueryClient,
  roomId: number,
  session: ReturnType<typeof getSessionSnapshot>,
): Promise<boolean> {
  if (!isSameSessionIdentity(session, getSessionSnapshot())) return false;
  const keys = threadLifecycleKeys(roomId, false);
  await Promise.all(keys.map((queryKey) => queryClient.cancelQueries({ queryKey })));
  if (!isSameSessionIdentity(session, getSessionSnapshot())) return false;
  await Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
  return isSameSessionIdentity(session, getSessionSnapshot()) && activeQueriesAreFresh(queryClient, ...keys);
}

export default function ThreadLifecycleActions({
  room,
  thread,
  onDeleted,
  onMerged,
}: {
  room: RoomResponse;
  thread: LifecycleThread;
  onDeleted: () => void | Promise<void>;
  onMerged: (targetNumber: number) => void | Promise<void>;
}) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const userId = getUserId();
  const queryClient = useQueryClient();
  const steward = isThreadSteward({ room, thread, userId, admin });
  const [mergeOpen, setMergeOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [targetValue, setTargetValue] = useState<string | number>("");
  const targetNumber = numberValue(targetValue);
  const targetQuery = useQuery({
    queryKey: roomKeys.threadDetail(room.id, mergeOpen ? targetNumber : null),
    queryFn: () => getLifecycleThread(room.id, targetNumber!),
    enabled: mergeOpen && targetNumber !== null && targetNumber !== thread.number,
    retry: false,
  });

  const target = targetQuery.data;
  const targetIssue = targetNumber === thread.number
    ? t("threadLifecycle.merge.same")
    : targetQuery.isError
      ? t("threadLifecycle.merge.notFound")
      : target?.mergedIntoThreadNumber != null
        ? t("threadLifecycle.merge.alreadyMerged")
        : target && !isThreadSteward({ room, thread: target, userId, admin })
          ? t("threadLifecycle.merge.permission")
          : null;
  const targetReady = target !== undefined && targetIssue === null;

  const merge = useMutation({
    mutationFn: async (into: number) => {
      const session = getSessionSnapshot();
      const echo = canCorrelateReply(API_BASE, window.location.href)
        ? beginMergeEcho(queryClient, room.id, thread.number)
        : undefined;
      try {
        await mergeThread(room.id, thread.number, into, echo?.id);
      } catch (error) {
        echo?.cancel();
        throw error;
      }
      let fresh = false;
      try {
        fresh = await refreshMergedThread(queryClient, room.id, session);
      } catch {
        // HTTP committed the merge; the exact live hint must take the ordinary refresh path when this refresh cannot prove freshness.
      }
      if (fresh) echo?.confirm();
      echo?.cancel();
      return isSameSessionIdentity(session, getSessionSnapshot());
    },
    onSuccess: async (sameSession, into) => {
      if (!sameSession) return;
      showSuccessToast(t("threadLifecycle.merge.success"));
      setMergeOpen(false);
      await onMerged(into);
    },
  });

  const remove = useMutation({
    mutationFn: async () => {
      const session = getSessionSnapshot();
      const commit = beginThreadDeleteCommit(queryClient, { roomId: room.id, threadNumber: thread.number });
      try {
        await deleteThread(room.id, thread.number);
        await commit.confirm();
      } catch (error) {
        if (!await commit.reject()) throw error;
      }
      return isSameSessionIdentity(session, getSessionSnapshot());
    },
    onSuccess: async (sameSession) => {
      if (!sameSession) return;
      showSuccessToast(t("threadLifecycle.delete.success"));
      setDeleteOpen(false);
      await onDeleted();
    },
  });

  if (room.archived || !steward) return null;
  const mergedSourceCount = thread.mergedSourceCount ?? 0;

  function closeMerge() {
    if (merge.isPending) return;
    setMergeOpen(false);
    setTargetValue("");
    merge.reset();
  }

  function openMerge() {
    merge.reset();
    setTargetValue("");
    setMergeOpen(true);
  }

  function closeDelete() {
    if (remove.isPending) return;
    setDeleteOpen(false);
    remove.reset();
  }

  function openDelete() {
    remove.reset();
    setDeleteOpen(true);
  }

  return (
    <>
      <Menu position="bottom-end" withinPortal>
        <Tooltip label={t("threadLifecycle.actions.label")}>
          <Menu.Target>
            <ActionIcon variant="subtle" aria-label={t("threadLifecycle.actions.label")}>
              <IconDotsVertical size={17} aria-hidden />
            </ActionIcon>
          </Menu.Target>
        </Tooltip>
        <Menu.Dropdown aria-label={t("threadLifecycle.actions.label")}>
          {thread.mergedIntoThreadNumber == null && (
            <Menu.Item leftSection={<IconGitMerge size={16} aria-hidden />} onClick={openMerge}>
              {t("threadLifecycle.actions.merge")}
            </Menu.Item>
          )}
          <Menu.Item color="red" leftSection={<IconTrash size={16} aria-hidden />} onClick={openDelete}>
            {t("threadLifecycle.actions.delete")}
          </Menu.Item>
        </Menu.Dropdown>
      </Menu>

      <Modal
        opened={mergeOpen}
        onClose={closeMerge}
        title={t("threadLifecycle.merge.title")}
        closeButtonProps={{ "aria-label": t("common.action.close") }}
        centered
      >
        <Stack gap="md">
          <List size="sm" spacing="xs">
            <List.Item>{t("threadLifecycle.merge.history", { number: thread.number })}</List.Item>
            <List.Item>{t("threadLifecycle.merge.replies")}</List.Item>
            <List.Item>{t("threadLifecycle.merge.targetKeeps")}</List.Item>
            <List.Item>{t("threadLifecycle.merge.watches", { number: thread.number })}</List.Item>
          </List>
          <NumberInput
            label={t("threadLifecycle.merge.targetLabel")}
            description={t("threadLifecycle.merge.targetDescription")}
            value={targetValue}
            onChange={setTargetValue}
            min={1}
            max={MAX_ID}
            allowDecimal={false}
            allowNegative={false}
            hideControls
          />
          {targetQuery.isFetching && <Text size="sm" c="dimmed">{t("common.loading")}</Text>}
          {targetReady && (
            <Paper withBorder p="sm">
              <Text size="sm" fw={600}>{t("threadLifecycle.merge.target", { number: target.number, title: target.title })}</Text>
              <Text size="xs" c="dimmed">{target.ownerName}</Text>
            </Paper>
          )}
          {targetIssue && <Alert color="orange" role="alert">{targetIssue}</Alert>}
          {merge.isError && (
            <FormErrorAlert>
              {saveErrorMessage(merge.error, t, {
                retryAfter: true,
                forbidden: "threadLifecycle.merge.forbidden",
                conflict: "threadLifecycle.merge.conflict",
                failedStatus: "threadLifecycle.merge.failedStatus",
                failed: "threadLifecycle.merge.failed",
              })}
            </FormErrorAlert>
          )}
          <Group justify="flex-end">
            <Button variant="default" data-autofocus onClick={closeMerge} disabled={merge.isPending}>
              {t("common.action.cancel")}
            </Button>
            <Button
              color="adder"
              onClick={() => targetReady && merge.mutate(target.number)}
              disabled={!targetReady}
              loading={merge.isPending}
            >
              {targetReady ? t("threadLifecycle.merge.confirmTarget", { number: target.number }) : t("threadLifecycle.merge.confirm")}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        opened={deleteOpen}
        onClose={closeDelete}
        title={t("threadLifecycle.delete.title", { number: thread.number })}
        closeButtonProps={{ "aria-label": t("common.action.close") }}
        centered
      >
        <Stack gap="md">
          <Text>
            {mergedSourceCount > 0
              ? t("threadLifecycle.delete.bodyWithChildren", { number: thread.number, count: mergedSourceCount })
              : t("threadLifecycle.delete.body", { number: thread.number })}
          </Text>
          {remove.isError && (
            <FormErrorAlert>
              {saveErrorMessage(remove.error, t, {
                retryAfter: true,
                forbidden: "threadLifecycle.delete.forbidden",
                conflict: "threadLifecycle.delete.conflict",
                failedStatus: "threadLifecycle.delete.failedStatus",
                failed: "threadLifecycle.delete.failed",
              })}
            </FormErrorAlert>
          )}
          <Group justify="flex-end">
            <Button variant="default" data-autofocus onClick={closeDelete} disabled={remove.isPending}>
              {t("common.action.cancel")}
            </Button>
            <Button color="red" onClick={() => remove.mutate()} loading={remove.isPending}>
              {t("threadLifecycle.delete.confirm")}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
