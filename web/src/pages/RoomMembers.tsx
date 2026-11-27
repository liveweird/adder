import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { Badge, Button, Group, Menu, Select, Stack, Table, Text } from "@mantine/core";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconLogout, IconShieldCheck, IconUserMinus, IconUserPlus, IconUsers } from "@tabler/icons-react";
import type { TFunction } from "i18next";
import { useAdmin } from "../auth";
import { ApiError } from "../api/http";
import { getSessionSnapshot, getUserId, isSameSessionIdentity, type SessionSnapshot } from "../api/session";
import { listRoomMembers, removeRoomMember, setRoomMemberRole, type RoomResponse, type RoomRole } from "../api/rooms";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmActionModal from "../components/ConfirmActionModal";
import FilterPanel from "../components/FilterPanel";
import FormErrorAlert from "../components/FormErrorAlert";
import RegistryListTable from "../components/RegistryListTable";
import RoomMembersAdd from "../components/RoomMembersAdd";
import RoomJoinRequests from "../components/RoomJoinRequests";
import RoomPage from "../components/RoomPage";
import RoomTabHeader from "../components/RoomTabHeader";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import PersonLink from "../components/PersonLink";
import { useRegistryListControls } from "../hooks/useRegistryListControls";
import { isOneOfOrNull, useStoredState } from "../hooks/useStoredState";
import { browserTimeZone, formatDate } from "../utils/formatDate";
import { PROBLEM_TYPES, isProblem } from "../utils/problems";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { canManageRoom, isAccessRefusal } from "../utils/roomAccess";
import { roomsPath } from "../utils/roomLinks";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import { MAX_USER_NAME_LENGTH } from "../utils/userForm";
import { homeKey, roomKeys } from "../utils/roomKeys";
import { clearRoomMessageRevisions } from "../utils/messageRevisionCache";
import { beginRoomMembershipEcho, refreshRoomMembership, settleRoomMembershipRefresh } from "../utils/roomMembershipRefresh";
import classes from "./RoomMembers.module.css";

const SORT_FIELDS = ["name", "role", "addedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const ROLES: readonly RoomRole[] = ["OWNER", "MEMBER"];
/** The view-settings namespace; each room has its own (`roomMembers.<id>`), so one room's filter never hides another's members. */
const SETTINGS_KEY = "roomMembers";

/** The words for a failed role change, removal or leave: the last-owner refusal says what to do, the rest map by status. */
function memberErrorMessage(error: unknown, t: TFunction, self: boolean): string {
  if (isProblem(error, PROBLEM_TYPES.lastOwner)) return t(self ? "rooms.members.lastOwnerLeave" : "rooms.members.lastOwner");
  return saveErrorMessage(error, t, {
    retryAfter: true,
    forbidden: "rooms.members.forbidden",
    notFound: "rooms.members.gone",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/** Who is leaving or being removed: the row's user (the leave is the caller's own id). */
type Departing = { userId: number; name: string };
type RemovalInput = { member: Departing; roomId: number; session: SessionSnapshot };

/** The page body of one loaded room: the add controls (its OWNER or an ADMIN), then the members as a paged table. */
function MembersBody({ room }: { room: RoomResponse }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const admin = useAdmin();
  const canManage = canManageRoom(room, admin);
  const me = getUserId();
  const settingsKey = `${SETTINGS_KEY}.${room.id}`;
  const refresh = () => refreshQueriesAfterMutation(queryClient, ["rooms"], homeKey);
  const [role, setRole] = useStoredState<RoomRole | null>(`${settingsKey}.filter.role`, null, isOneOfOrNull(ROLES));
  const { nameFilter, setNameFilter, debouncedName, nameFilterActive, page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    useRegistryListControls<SortField>({ settingsKey, sortFields: SORT_FIELDS, initialSortField: "name", extraFilterDeps: [role] });
  // `removing` outlives the dialog: its text and the failure's wording stay put through the close transition, `confirming` opens it.
  const [removing, setRemoving] = useState<Departing | null>(null);
  const [confirming, setConfirming] = useState(false);
  const leaving = removing !== null && removing.userId === me;
  // A failed add's words live here, not in the add controls: a 403 takes those away and the user must still see why.
  const [addFailure, setAddFailure] = useState<string | null>(null);
  const activeRoom = useRef<number | null>(room.id);
  useLayoutEffect(() => {
    activeRoom.current = room.id;
    return () => { activeRoom.current = null; };
  }, [room.id]);

  // Under the ["rooms"] prefix: any room refresh (the header's member count included) re-reads the page.
  const { data, isLoading, isError, error } = useQuery({
    queryKey: [...roomKeys.members(room.id), page, pageSize, sortParam, debouncedName, role],
    queryFn: () => listRoomMembers(room.id, { page, pageSize, sort: sortParam, name: debouncedName || undefined, role: role ?? undefined }),
    placeholderData: keepPreviousData,
  });
  const memberValues = new Set(data?.items.map((member) => String(member.userId)));
  // The page was emptied under the reader (the last rows on it were removed): step back to the first.
  if (data && data.items.length === 0 && page > 1) setPage(1);
  // The members answering 404 means the room is gone or the caller no longer reads it: re-read the room, which then shows not-found.
  const membersGone = error instanceof ApiError && error.status === 404;
  useEffect(() => {
    if (membersGone) void queryClient.invalidateQueries({ queryKey: roomKeys.detail(room.id) });
  }, [membersGone, queryClient, room.id]);

  // After a leave the room's own queries would only answer 404: they are dropped, and the rooms re-read, once the page has gone
  // (never while it is still mounted, or the invalidation would refetch what was just removed).
  const left = useRef(false);
  useEffect(
    () => () => {
      if (!left.current) return;
      clearRoomMessageRevisions(queryClient, room.id);
      for (const key of [roomKeys.detail, roomKeys.members, roomKeys.workflow]) queryClient.removeQueries({ queryKey: key(room.id) });
      void refreshQueriesAfterMutation(queryClient, ["rooms"]);
    },
    [queryClient, room.id],
  );

  // A 403, 404 or 409 says the caller's standing or the roster moved (demoted, removed, someone else acted first): show the truth.
  const refreshOnRefusal = async (failure: unknown) => {
    if (isAccessRefusal(failure)) await refresh();
  };
  const changeRole = useMutation({
    mutationFn: ({ userId, to }: { userId: number; to: RoomRole }) => setRoomMemberRole(room.id, userId, to),
    onSuccess: async (_none, { userId, to }) => {
      clearRoomMessageRevisions(queryClient, room.id);
      showSuccessToast(t(userId === me && to === "MEMBER" ? "rooms.members.toast.steppedDown" : "rooms.members.toast.roleChanged"));
      await refresh();
    },
    onError: refreshOnRefusal,
  });
  const remove = useMutation({
    mutationFn: async ({ member, roomId, session }: RemovalInput) => {
      // Leaving revokes this room and follows its existing navigation/cleanup path; it is never correlated.
      if (member.userId === me) {
        await removeRoomMember(roomId, member.userId);
        return { member, roomId, current: isSameSessionIdentity(session, getSessionSnapshot()) };
      }
      const echo = beginRoomMembershipEcho(queryClient, roomId, "room.member_removed");
      try {
        await removeRoomMember(roomId, member.userId, echo?.id);
      } catch (error) {
        echo?.cancel();
        throw error;
      }
      return { member, roomId, current: await settleRoomMembershipRefresh(queryClient, session, echo) };
    },
    onSuccess: async ({ member, roomId, current }) => {
      if (!current || activeRoom.current !== roomId) return;
      setConfirming(false);
      if (member.userId === me) {
        // A leave: go, and let the unmount clean up (above).
        showSuccessToast(t("rooms.members.toast.left"));
        left.current = true;
        navigate(roomsPath, { replace: true });
        return;
      }
      showSuccessToast(t("rooms.members.toast.removed"));
    },
    // The failure shows inline above the table, not inside the closed dialog.
    onError: async (failure, input) => {
      if (activeRoom.current !== input.roomId || !isSameSessionIdentity(input.session, getSessionSnapshot())) return;
      setConfirming(false);
      if (isAccessRefusal(failure)) await refreshRoomMembership(queryClient, input.session);
    },
  });

  const columnCount = 4;
  return (
    <Stack gap="xl" className={classes.page}>
      <RoomTabHeader
        title={t("rooms.members.title")}
        description={t("rooms.members.description")}
        actions={
          <>
            {/* Everyone in the room can leave it. The server refuses the last owner. */}
            {room.role !== null && me !== null && (
              <Button
                variant="default"
                leftSection={<IconLogout size={16} />}
                onClick={() => {
                  changeRole.reset();
                  remove.reset();
                  setRemoving({ userId: me, name: "" });
                  setConfirming(true);
                }}
              >
                {t("rooms.members.leave")}
              </Button>
            )}
            {room.role === "OWNER" && room.ownerCount > 1 && me !== null && (
              <Button
                variant="default"
                leftSection={<IconShieldCheck size={16} aria-hidden="true" />}
                loading={changeRole.isPending && changeRole.variables?.userId === me}
                onClick={() => {
                  remove.reset();
                  changeRole.mutate({ userId: me, to: "MEMBER" });
                }}
              >
                {t("rooms.members.stepDown")}
              </Button>
            )}
            {canManage && (
              <Button component="a" href="#room-members-add" leftSection={<IconUserPlus size={16} aria-hidden />}>
                {t("rooms.members.addTitle")}
              </Button>
            )}
          </>
        }
      />
      <Stack gap="md" component="section" aria-label={t("rooms.members.title")}>
        <FilterPanel activeFilterCount={(nameFilterActive ? 1 : 0) + (role ? 1 : 0)} storageKey={settingsKey}>
          <ClearableTextInput
            label={t("common.field.name")}
            value={nameFilter}
            onChange={setNameFilter}
            clearLabel={t("common.filter.clearName")}
            maxLength={MAX_USER_NAME_LENGTH}
          />
          <Select
            label={t("common.field.role")}
            value={role}
            onChange={(next) => setRole(next as RoomRole | null)}
            clearable
            placeholder={t("common.state.any")}
            data={ROLES.map((value) => ({ value, label: t(`rooms.role.${value}`) }))}
          />
        </FilterPanel>
        {changeRole.isError && (
          <FormErrorAlert title={t("rooms.members.roleFailed")}>{memberErrorMessage(changeRole.error, t, false)}</FormErrorAlert>
        )}
        {remove.isError && remove.variables?.roomId === room.id && isSameSessionIdentity(remove.variables.session, getSessionSnapshot()) && (
          <FormErrorAlert title={t(leaving ? "rooms.members.leaveFailed" : "rooms.members.removeFailed")}>
            {memberErrorMessage(remove.error, t, leaving)}
          </FormErrorAlert>
        )}
        <div className={classes.tableRegion}>
          <RegistryListTable
            label={t("rooms.members.title")}
            errorTitle={t("rooms.members.loadFailed")}
            error={error}
            isError={isError}
            isLoading={isLoading}
            hasData={Boolean(data)}
            rowCount={data?.items.length ?? 0}
            columnCount={columnCount}
            emptyIcon={IconUsers}
            emptyLabel={t("rooms.members.empty")}
            header={
              <Table.Tr role="row">
                <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
                <SortHeader field="role" label={t("common.field.role")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
                <SortHeader field="addedAt" label={t("rooms.members.column.added")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
                <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
              </Table.Tr>
            }
            rows={data?.items.map((member) => {
              const self = member.userId === me;
              const promote = member.role === "MEMBER";
              return (
                <Table.Tr key={member.userId} role="row" className={classes.memberRow}>
                  <Table.Td role="cell" className={classes.identityCell}>
                    <Stack gap={2} className={classes.identity}>
                      <Group gap="xs" wrap="nowrap">
                        <PersonLink id={member.userId} name={member.name} size="sm" fw={600} />
                        {self && (
                          <Badge size="xs" variant="light">
                            {t("rooms.members.you")}
                          </Badge>
                        )}
                      </Group>
                      <Text size="xs" c="dimmed" className={classes.email}>
                        {member.email}
                      </Text>
                    </Stack>
                  </Table.Td>
                  <Table.Td role="cell" className={classes.metaCell}>
                    <Text span size="xs" c="dimmed" className={classes.mobileLabel}>
                      {t("common.field.role")}
                    </Text>
                    <Badge color="gray" variant={member.role === "OWNER" ? "outline" : "light"} radius="sm">
                      {t(`rooms.role.${member.role}`)}
                    </Badge>
                  </Table.Td>
                  <Table.Td role="cell" className={classes.metaCell}>
                    <Text span size="xs" c="dimmed" className={classes.mobileLabel}>
                      {t("rooms.members.column.added")}
                    </Text>
                    <Text size="sm" className={classes.date}>
                      {formatDate(member.addedAt, "—", browserTimeZone())}
                    </Text>
                  </Table.Td>
                  <Table.Td role="cell" className={classes.actionsCell} ta="right">
                    {/* A manager changes or removes the others (the server guards both); leaving is the section's own button. */}
                    {canManage && !self && (
                      <RowActionsMenu
                        label={t("common.table.operationsAria", { name: member.name })}
                        loading={changeRole.isPending && changeRole.variables?.userId === member.userId}
                        disabled={changeRole.isPending}
                      >
                        <Menu.Item
                          leftSection={<IconShieldCheck size={14} />}
                          onClick={() => {
                            remove.reset();
                            changeRole.mutate({ userId: member.userId, to: promote ? "OWNER" : "MEMBER" });
                          }}
                        >
                          {t(promote ? "rooms.members.makeOwner" : "rooms.members.makeMember")}
                        </Menu.Item>
                        <Menu.Divider />
                        <Menu.Item
                          color="red"
                          leftSection={<IconUserMinus size={14} />}
                          onClick={() => {
                            changeRole.reset();
                            remove.reset();
                            setRemoving({ userId: member.userId, name: member.name });
                            setConfirming(true);
                          }}
                        >
                          {t("rooms.members.remove")}
                        </Menu.Item>
                      </RowActionsMenu>
                    )}
                  </Table.Td>
                </Table.Tr>
              );
            })}
            total={data?.total ?? 0}
            page={page}
            pageSize={pageSize}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
          />
        </div>
      </Stack>

      {room.role === "OWNER" && room.accessType === "RESTRICTED_VISIBLE" && <RoomJoinRequests roomId={room.id} />}
      {canManage && <RoomMembersAdd room={room} memberValues={memberValues} onAddFailure={setAddFailure} />}
      {addFailure && <FormErrorAlert title={t("rooms.members.addFailed")}>{addFailure}</FormErrorAlert>}
      <ConfirmActionModal
        opened={confirming}
        onClose={() => setConfirming(false)}
        title={t(leaving ? "rooms.members.leaveTitle" : "rooms.members.removeTitle")}
        message={
          removing
            ? leaving
              ? t("rooms.members.leaveBody", { room: room.name })
              : t("rooms.members.removeBody", { name: removing.name, room: room.name })
            : null
        }
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t(leaving ? "rooms.members.leave" : "rooms.members.remove")}
        onConfirm={() => removing && remove.mutate({ member: removing, roomId: room.id, session: getSessionSnapshot() })}
        loading={remove.isPending}
      />
    </Stack>
  );
}

/**
 * `/rooms/:id/members`: who is in the room, as a paged table (name and role filters; sort by name, role or when they joined).
 * The room's OWNER or an ADMIN adds people (one from the directory, or a whole team after a count-stating confirm) and changes
 * or removes the others; everyone in the room, a plain member included, reads the list and can leave from the section header. The last owner
 * cannot leave or be demoted or removed (the server's 409 `last-owner`, worded inline).
 */
export default function RoomMembers() {
  const { id } = useParams();
  // Keyed by the room: the per-room view settings are read once, on mount.
  return <RoomPage idParam={id} tab="members">{(room) => <MembersBody key={room.id} room={room} />}</RoomPage>;
}
