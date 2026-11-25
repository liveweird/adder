import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Loader, Menu, Stack, Text } from "@mantine/core";
import { IconArrowRight, IconArrowsExchange, IconChevronDown, IconLock, IconLockOpen } from "@tabler/icons-react";
import { useQueryClient } from "@tanstack/react-query";
import { getUserId } from "../api/session";
import type { RoomResponse, RoomWorkflow } from "../api/rooms";
import { setThreadStatus, type ThreadResponse } from "../api/threads";
import { useAdmin } from "../auth";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { homeKey, roomKeys } from "../utils/roomKeys";
import { isStaleWrite, threadStatusErrorMessage } from "../utils/threadActions";
import { isThreadSteward, statusMoveBlock } from "../utils/threadAccess";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "./ConfirmActionModal";
import FormErrorAlert from "./FormErrorAlert";
import ThreadStatusPill from "./ThreadStatusPill";
import classes from "./ThreadStatusControl.module.css";

type WorkflowStatus = RoomWorkflow["statuses"][number];

export default function ThreadStatusControl({
  room,
  thread,
  statuses,
}: {
  room: RoomResponse;
  thread: ThreadResponse;
  statuses: readonly WorkflowStatus[] | undefined;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const admin = useAdmin();
  const steward = isThreadSteward({ room, thread, userId: getUserId(), admin });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [closing, setClosing] = useState<{ target: WorkflowStatus; from: string } | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);

  const available = (statuses ?? []).filter((status) => status.key !== thread.status && statusMoveBlock(room, thread, steward, status) === null);
  const [first] = available;
  const hasMenu = available.length > 1;
  const readOnlyNote = thread.mergedIntoThreadNumber != null ? null : room.archived ? t("threads.status.archivedNote")
    : thread.closed && !steward ? t("threads.status.closedNote")
    : statuses && !first ? t("threads.status.noMoves") : null;

  if (menuOpen && !hasMenu) setMenuOpen(false);

  // If a live update invalidates the pending close, require a fresh choice.
  if (closing && !saving && (thread.closed || thread.status !== closing.from
    || !available.some((status) => status.key === closing.target.key && status.category === "CLOSED" && status.label === closing.target.label))) {
    setClosing(null);
  }

  function actionFor(target: WorkflowStatus) {
    if (thread.closed && target.category === "OPEN") return { label: t("threads.status.reopenAs", { status: target.label }), icon: IconLockOpen };
    if (!thread.closed && target.category === "CLOSED") return { label: t("threads.status.closeAs", { status: target.label }), icon: IconLock };
    return { label: t("threads.status.moveTo", { status: target.label }), icon: IconArrowRight };
  }

  async function move(key: string) {
    if (saving) return;
    setError(null);
    setSaving(true);
    try {
      await setThreadStatus(room.id, thread.number, key);
      showSuccessToast(t("threads.toast.statusChanged"));
      await refreshQueriesAfterMutation(queryClient, roomKeys.threads(room.id), roomKeys.rail, roomKeys.workflow(room.id), homeKey);
    } catch (err) {
      setError(threadStatusErrorMessage(err, t));
      if (isStaleWrite(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    } finally {
      setClosing(null);
      setSaving(false);
    }
  }

  function choose(target: WorkflowStatus) {
    // The menu item may unmount before the confirmation can remember where to return focus.
    buttonRef.current?.focus({ preventScroll: true });
    if (!thread.closed && target.category === "CLOSED") setClosing({ target, from: thread.status });
    else void move(target.key);
  }

  const direct = first && !hasMenu ? actionFor(first) : null;
  const ActionIcon = direct?.icon ?? IconArrowsExchange;
  const button = first && (
    <Button
      ref={buttonRef}
      type="button"
      size="compact-sm"
      classNames={{ root: classes.action, label: classes.actionLabel }}
      aria-busy={saving || undefined}
      aria-disabled={saving || undefined}
      leftSection={saving ? <Loader size={16} color="white" aria-hidden /> : <ActionIcon size={16} aria-hidden />}
      rightSection={hasMenu ? <IconChevronDown size={14} aria-hidden /> : undefined}
      onClick={direct ? () => !saving && choose(first) : undefined}
    >
      {direct?.label ?? t("threads.status.change")}
    </Button>
  );

  return (
    <Stack gap="xs">
      <div className={classes.bar}>
        <div className={classes.current} role="status" aria-label={t("threads.status.label")}>
          <ThreadStatusPill statuses={statuses} statusKey={thread.status} wrap />
        </div>
        {hasMenu ? (
          <Menu opened={menuOpen && !saving} onChange={(opened) => setMenuOpen(opened && !saving)} withInitialFocusPlaceholder={false}>
            <Menu.Target>{button}</Menu.Target>
            <Menu.Dropdown className={classes.menu}>
              {available.map((target) => {
                const { label, icon: Icon } = actionFor(target);
                return (
                  <Menu.Item
                    key={target.key}
                    className={classes.menuItem}
                    data-autofocus={target === first || undefined}
                    leftSection={<Icon size={16} aria-hidden />}
                    onClick={() => choose(target)}
                  >
                    {label}
                  </Menu.Item>
                );
              })}
            </Menu.Dropdown>
          </Menu>
        ) : button}
        {readOnlyNote && <Text size="xs" c="dimmed">{readOnlyNote}</Text>}
      </div>
      {error && <FormErrorAlert>{error}</FormErrorAlert>}
      <ConfirmActionModal
        opened={closing !== null}
        onClose={() => setClosing(null)}
        title={t("threads.status.closeTitle")}
        message={t("threads.status.closeMessage", { number: thread.number, status: closing?.target.label ?? "" })}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t("threads.status.closeConfirm")}
        confirmColor="adder"
        loading={saving}
        onConfirm={() => closing && void move(closing.target.key)}
      />
    </Stack>
  );
}
