import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from "react";
import { ActionIcon, Badge, Button, Group, Menu, Stack, Text, TextInput, Title, VisuallyHidden } from "@mantine/core";
import {
  IconArchive, IconArrowDown, IconArrowUp, IconCheck, IconChevronDown, IconChevronRight, IconDotsVertical,
  IconGripVertical, IconPencil, IconPlus, IconTrash, IconX,
} from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { RoomListItem } from "../api/rooms";
import {
  deleteOrganizationGroup, moveOrganizationGroup, moveOrganizationGroupTo, moveOrganizationRoom,
  placeOrganizationRoom, MAX_ROOM_PLACEMENTS, type RoomOrganizationDestination, type RoomOrganizationDraft,
} from "../utils/roomOrganizationDraft";
import classes from "./RoomOrganizationEditor.module.css";
import { useOrganizationDrag, type OrganizationDragSource } from "./useOrganizationDrag";

const MAX_ROOM_GROUPS = 50;
const MAX_ROOM_GROUP_NAME_LENGTH = 60;
const UNGROUPED_DATA_VALUE = "ungrouped";

type Change = (draft: RoomOrganizationDraft) => void;

function ItemMenu({ name, disabled, returnFocus = true, triggerRef, children }: {
  name: string;
  disabled: boolean;
  returnFocus?: boolean;
  triggerRef?: (node: HTMLButtonElement | null) => void;
  children: React.ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <Menu position="bottom-end" returnFocus={returnFocus}>
      <Menu.Target>
        <ActionIcon ref={triggerRef} size="sm" variant="subtle" disabled={disabled} aria-label={t("rooms.organization.actions", { name })}>
          <IconDotsVertical size={17} aria-hidden="true" />
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown>{children}</Menu.Dropdown>
    </Menu>
  );
}

function MoveToGroupMenu({ roomId, roomName, draft, visibleUngroupedIds, onChange, onMoved, restoreFocus, announce }: {
  roomId: number;
  roomName: string;
  draft: RoomOrganizationDraft;
  visibleUngroupedIds: readonly number[];
  onChange: Change;
  onMoved: (groupId: string | null) => void;
  restoreFocus: () => void;
  announce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const destinations = [
    ...draft.groups.map(({ id, name }) => ({ id, name })),
    { id: null, name: t("rooms.organization.ungrouped") },
  ];
  return (
    <Menu.Sub>
      <Menu.Sub.Target>
        <Menu.Sub.Item>{t("rooms.organization.moveToGroup")}</Menu.Sub.Item>
      </Menu.Sub.Target>
      <Menu.Sub.Dropdown>
        {destinations.map(({ id, name }) => {
          const next = placeOrganizationRoom(draft, roomId, { groupId: id, position: { kind: "end" } }, visibleUngroupedIds);
          return (
            <Menu.Item
              key={id ?? UNGROUPED_DATA_VALUE}
              disabled={next === draft}
              onClick={() => {
              onChange(next);
              onMoved(id);
              announce(t("rooms.organization.movedToGroup", { name: roomName, group: name }));
              window.requestAnimationFrame(restoreFocus);
            }}
          >
            {name}
          </Menu.Item>
          );
        })}
      </Menu.Sub.Dropdown>
    </Menu.Sub>
  );
}

function RoomRow({ room, index, peers, draft, disabled, visibleUngroupedIds, onChange, onMoved, dragHandlers, actionRefs, announce }: {
  room: RoomListItem;
  index: number;
  peers: readonly RoomListItem[];
  draft: RoomOrganizationDraft;
  disabled: boolean;
  visibleUngroupedIds: readonly number[];
  onChange: Change;
  onMoved: (groupId: string | null) => void;
  dragHandlers: ReturnType<typeof useOrganizationDrag>["handlers"];
  actionRefs: RefObject<Map<number, HTMLButtonElement>>;
  announce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const roomIds = peers.map(({ id }) => id);
  const source: OrganizationDragSource = { kind: "room", id: room.id, name: room.name };
  const movedUp = moveOrganizationRoom(draft, room.id, -1, roomIds);
  const movedDown = moveOrganizationRoom(draft, room.id, 1, roomIds);
  const move = (next: RoomOrganizationDraft, delta: -1 | 1) => {
    if (next === draft) return;
    onChange(next);
    announce(t(delta === -1 ? "rooms.organization.movedBefore" : "rooms.organization.movedAfter", {
      name: room.name,
      target: peers[index + delta]?.name ?? "",
    }));
    window.requestAnimationFrame(() => actionRefs.current.get(room.id)?.focus());
  };
  return (
    <li className={classes.roomItem} data-room-id={room.id}>
      <div className={classes.roomRow}>
        <ActionIcon
          size="sm"
          variant="subtle"
          className={classes.dragHandle}
          tabIndex={-1}
          disabled={disabled}
          aria-label={t("rooms.organization.drag", { name: room.name })}
          {...dragHandlers(source)}
        >
          <IconGripVertical size={17} aria-hidden="true" />
        </ActionIcon>
        <Group gap="xs" wrap="nowrap" className={classes.roomName}>
          <Text fw={550} size="sm" truncate="end">{room.name}</Text>
          {room.archived && (
            <Badge size="xs" variant="light" leftSection={<IconArchive size={10} aria-hidden="true" />}>
              {t("rooms.archivedBadge")}
            </Badge>
          )}
        </Group>
        <ItemMenu
          name={room.name}
          disabled={disabled}
          triggerRef={(node) => {
            if (node) actionRefs.current.set(room.id, node);
            else actionRefs.current.delete(room.id);
          }}
        >
          <Menu.Item leftSection={<IconArrowUp size={14} />} disabled={index === 0 || movedUp === draft} onClick={() => move(movedUp, -1)}>
            {t("rooms.organization.moveUpItem")}
          </Menu.Item>
          <Menu.Item leftSection={<IconArrowDown size={14} />} disabled={index === peers.length - 1 || movedDown === draft} onClick={() => move(movedDown, 1)}>
            {t("rooms.organization.moveDownItem")}
          </Menu.Item>
          <Menu.Divider />
          <MoveToGroupMenu
            roomId={room.id}
            roomName={room.name}
            draft={draft}
            visibleUngroupedIds={visibleUngroupedIds}
            onChange={onChange}
            onMoved={onMoved}
            restoreFocus={() => actionRefs.current.get(room.id)?.focus()}
            announce={announce}
          />
        </ItemMenu>
      </div>
    </li>
  );
}

function RoomList({ rooms, draft, disabled, hasUnloadedRooms, visibleUngroupedIds, onChange, onMoved, dragHandlers, actionRefs, announce }: {
  rooms: readonly RoomListItem[];
  draft: RoomOrganizationDraft;
  disabled: boolean;
  hasUnloadedRooms: boolean;
  visibleUngroupedIds: readonly number[];
  onChange: Change;
  onMoved: (groupId: string | null) => void;
  dragHandlers: ReturnType<typeof useOrganizationDrag>["handlers"];
  actionRefs: RefObject<Map<number, HTMLButtonElement>>;
  announce: (message: string) => void;
}) {
  const { t } = useTranslation();
  if (rooms.length === 0) return (
    <Text size="sm" c="dimmed" className={classes.empty}>
      {hasUnloadedRooms ? t("rooms.organization.roomsNotLoaded") : t("rooms.organization.emptyGroup")}
    </Text>
  );
  return (
    <ul className={classes.roomList} role="list">
      {rooms.map((room, index) => (
        <RoomRow
          key={room.id}
          room={room}
          index={index}
          peers={rooms}
          draft={draft}
          disabled={disabled}
          visibleUngroupedIds={visibleUngroupedIds}
          onChange={onChange}
          onMoved={onMoved}
          dragHandlers={dragHandlers}
          actionRefs={actionRefs}
          announce={announce}
        />
      ))}
    </ul>
  );
}

function GroupNameEditor({ groupId, originalName, value, duplicate, disabled, onValue, onCommit, onCancel }: {
  groupId: string;
  originalName: string;
  value: string;
  duplicate: boolean;
  disabled: boolean;
  onValue: (value: string) => void;
  onCommit: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => ref.current?.focus(), []);
  const canCommit = !disabled && !duplicate && value.trim() !== "";
  return (
    <Group wrap="nowrap" gap={4} className={classes.renameEditor}>
      <TextInput
        ref={ref}
        className={classes.renameInput}
        size="xs"
        value={value}
        maxLength={MAX_ROOM_GROUP_NAME_LENGTH}
        disabled={disabled}
        error={duplicate ? t("rooms.organization.duplicateName") : undefined}
        aria-label={t("rooms.organization.rename", { name: originalName })}
        onChange={(event) => onValue(event.currentTarget.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter" && !event.nativeEvent.isComposing && canCommit) onCommit();
          if (event.key === "Escape") onCancel();
        }}
        data-group-edit={groupId}
      />
      <ActionIcon variant="subtle" disabled={!canCommit} aria-label={t("rooms.organization.applyName")} onClick={onCommit}>
        <IconCheck size={16} aria-hidden="true" />
      </ActionIcon>
      <ActionIcon variant="subtle" disabled={disabled} aria-label={t("common.action.cancel")} onClick={onCancel}>
        <IconX size={16} aria-hidden="true" />
      </ActionIcon>
    </Group>
  );
}

export default function RoomOrganizationEditor({ rooms, draft, onChange, onSave, onDiscard, saving, reloading = false, saveBlocked = false, dirty }: {
  rooms: readonly RoomListItem[];
  draft: RoomOrganizationDraft;
  onChange: Change;
  onSave: () => void;
  onDiscard: () => void;
  saving: boolean;
  reloading?: boolean;
  saveBlocked?: boolean;
  dirty: boolean;
}) {
  const { t } = useTranslation();
  const disabled = saving || reloading;
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [adding, setAdding] = useState(false);
  const [newName, setNewName] = useState("");
  const [renaming, setRenaming] = useState<{ id: string; original: string; value: string } | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const previousDirty = useRef(dirty);
  const newNameRef = useRef<HTMLInputElement>(null);
  const newGroupButtonRef = useRef<HTMLButtonElement>(null);
  const actionRefs = useRef(new Map<number, HTMLButtonElement>());
  const groupActionRefs = useRef(new Map<string, HTMLButtonElement>());
  const roomById = useMemo(() => new Map(rooms.map((room) => [room.id, room])), [rooms]);
  const placementIds = useMemo(() => new Set(draft.rooms.map(({ roomId }) => roomId)), [draft.rooms]);
  const visible = useCallback((groupId: string | null) => {
    const placed = draft.rooms.filter((room) => room.groupId === groupId)
      .map(({ roomId }) => roomById.get(roomId)).filter((room): room is RoomListItem => room !== undefined);
    return groupId === null ? [...placed, ...rooms.filter((room) => !placementIds.has(room.id))] : placed;
  }, [draft.rooms, placementIds, roomById, rooms]);
  const visibleUngroupedIds = useMemo(() => visible(null).map(({ id }) => id), [visible]);
  const duplicateName = useCallback((name: string, ownId?: string) => draft.groups.some((group) => (
    group.id !== ownId && group.name.trim().localeCompare(name.trim(), undefined, { sensitivity: "accent" }) === 0
  )), [draft.groups]);
  const duplicateRename = renaming !== null && duplicateName(renaming.value, renaming.id);
  const invalidNames = draft.groups.some((group) => group.name.trim() === "" || duplicateName(group.name, group.id));
  const announce = useCallback((message: string) => {
    setAnnouncement("");
    window.setTimeout(() => setAnnouncement(message), 0);
  }, []);
  const revealGroup = useCallback((groupId: string | null) => {
    const key = groupId ?? UNGROUPED_DATA_VALUE;
    setCollapsed((current) => {
      if (!current.has(key)) return current;
      const next = new Set(current);
      next.delete(key);
      return next;
    });
  }, []);
  const resetKey = `${disabled}:${draft.groups.map(({ id }) => id).join(",")}:${draft.rooms.map(({ roomId, groupId }) => `${roomId}:${groupId}`).join(",")}`;
  const onGroupDrop = useCallback((groupId: string, beforeGroupId: string | null) => {
    const next = moveOrganizationGroupTo(draft, groupId, beforeGroupId);
    if (next === draft) return;
    onChange(next);
    const name = draft.groups.find(({ id }) => id === groupId)?.name ?? "";
    const target = draft.groups.find(({ id }) => id === beforeGroupId)?.name;
    announce(target
      ? t("rooms.organization.movedBefore", { name, target })
      : t("rooms.organization.movedToEnd", { name }));
  }, [announce, draft, onChange, t]);
  const onRoomDrop = useCallback((roomId: number, destination: RoomOrganizationDestination) => {
    const next = placeOrganizationRoom(draft, roomId, destination, visibleUngroupedIds);
    if (next === draft) return;
    onChange(next);
    revealGroup(destination.groupId);
    const name = roomById.get(roomId)?.name ?? "";
    if (destination.position.kind === "end") {
      const group = draft.groups.find(({ id }) => id === destination.groupId)?.name ?? t("rooms.organization.ungrouped");
      announce(t("rooms.organization.movedToGroup", { name, group }));
    } else {
      const target = roomById.get(destination.position.roomId)?.name ?? "";
      announce(t(destination.position.kind === "before" ? "rooms.organization.movedBefore" : "rooms.organization.movedAfter", { name, target }));
    }
    window.requestAnimationFrame(() => actionRefs.current.get(roomId)?.focus());
  }, [announce, draft, onChange, revealGroup, roomById, t, visibleUngroupedIds]);
  const onDragCancel = useCallback(() => announce(t("rooms.organization.dragCancelled")), [announce, t]);
  const canDropGroup = useCallback((groupId: string, beforeGroupId: string | null) => (
    moveOrganizationGroupTo(draft, groupId, beforeGroupId) !== draft
  ), [draft]);
  const canDropRoom = useCallback((roomId: number, destination: RoomOrganizationDestination) => (
    placeOrganizationRoom(draft, roomId, destination, visibleUngroupedIds) !== draft
  ), [draft, visibleUngroupedIds]);
  const drag = useOrganizationDrag({
    disabled, resetKey, canDropGroup, canDropRoom, onGroupDrop, onRoomDrop, onCancel: onDragCancel,
  });

  useEffect(() => {
    if (adding) newNameRef.current?.focus();
  }, [adding]);
  useEffect(() => {
    const discarded = previousDirty.current && !dirty;
    previousDirty.current = dirty;
    const groupGone = renaming !== null && !draft.groups.some(({ id }) => id === renaming.id);
    if (!reloading && !discarded && !groupGone) return;
    const timeout = window.setTimeout(() => {
      setNewName("");
      setAdding(false);
      setRenaming(null);
    }, 0);
    return () => window.clearTimeout(timeout);
  }, [dirty, draft.groups, reloading, renaming]);
  const finishRename = (apply: boolean) => {
    if (!renaming) return;
    const { id, value, original } = renaming;
    const name = value.trim();
    if (apply && name !== original) {
      onChange({ ...draft, groups: draft.groups.map((group) => group.id === id ? { ...group, name } : group) });
    }
    setRenaming(null);
    window.requestAnimationFrame(() => groupActionRefs.current.get(id)?.focus());
  };
  const createGroup = () => {
    const name = newName.trim();
    if (name === "" || duplicateName(name) || draft.groups.length >= MAX_ROOM_GROUPS) return;
    onChange({ ...draft, groups: [...draft.groups, { id: crypto.randomUUID(), name }] });
    setNewName("");
    setAdding(false);
  };
  const cancelAdding = () => {
    setNewName("");
    setAdding(false);
    window.requestAnimationFrame(() => newGroupButtonRef.current?.focus());
  };
  const moveGroup = (groupId: string, name: string, delta: -1 | 1) => {
    const next = moveOrganizationGroup(draft, groupId, delta);
    if (next === draft) return;
    onChange(next);
    const target = draft.groups[draft.groups.findIndex(({ id }) => id === groupId) + delta]?.name ?? "";
    announce(t(delta === -1 ? "rooms.organization.movedBefore" : "rooms.organization.movedAfter", { name, target }));
    window.requestAnimationFrame(() => groupActionRefs.current.get(groupId)?.focus());
  };

  return (
    <Stack gap="md" className={classes.editor} data-dragging={drag.active ? "true" : undefined}>
      <VisuallyHidden role="status" aria-live="polite">{announcement}</VisuallyHidden>
      <Group justify="space-between" align="center" className={classes.toolbar}>
        <Text size="sm" c="dimmed">{t("rooms.organization.dragHint")}</Text>
        <Button
          ref={newGroupButtonRef}
          size="xs"
          variant="default"
          leftSection={<IconPlus size={15} aria-hidden="true" />}
          disabled={disabled || draft.groups.length >= MAX_ROOM_GROUPS}
          onClick={() => setAdding(true)}
        >
          {t("rooms.organization.newGroup")}
        </Button>
      </Group>
      {adding && (
        <Group align="flex-start" wrap="nowrap" className={classes.newGroupForm}>
          <TextInput
            ref={newNameRef}
            className={classes.newGroupInput}
            size="sm"
            label={t("rooms.organization.groupName")}
            placeholder={t("rooms.organization.newGroupPlaceholder")}
            value={newName}
            disabled={disabled}
            maxLength={MAX_ROOM_GROUP_NAME_LENGTH}
            error={newName.trim() !== "" && duplicateName(newName) ? t("rooms.organization.duplicateName") : undefined}
            onChange={(event) => setNewName(event.currentTarget.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.nativeEvent.isComposing) createGroup();
              if (event.key === "Escape") cancelAdding();
            }}
          />
          <Button size="sm" className={classes.newGroupAction} disabled={disabled || newName.trim() === "" || duplicateName(newName)} onClick={createGroup}>
            {t("rooms.organization.createGroup")}
          </Button>
          <ActionIcon size="lg" variant="subtle" className={classes.newGroupAction} disabled={disabled} aria-label={t("common.action.cancel")} onClick={cancelAdding}>
            <IconX size={17} aria-hidden="true" />
          </ActionIcon>
        </Group>
      )}
      {draft.groups.length >= MAX_ROOM_GROUPS && <Text size="sm" c="dimmed">{t("rooms.organization.limit", { count: MAX_ROOM_GROUPS })}</Text>}
      {draft.rooms.length + rooms.filter((room) => !placementIds.has(room.id)).length >= MAX_ROOM_PLACEMENTS && (
        <Text size="sm" c="dimmed">{t("rooms.organization.placementLimit", { count: MAX_ROOM_PLACEMENTS })}</Text>
      )}

      <div className={classes.tree}>
        {draft.groups.map((group, index) => {
          const groupRooms = visible(group.id);
          const isCollapsed = collapsed.has(group.id);
          const titleId = `room-group-${group.id}`;
          const source: OrganizationDragSource = { kind: "group", id: group.id, name: group.name };
          return (
            <section key={group.id} className={classes.group} data-group-id={group.id} aria-labelledby={titleId}>
              <div className={classes.groupHeader} data-group-header>
                <ActionIcon
                  size="sm" variant="subtle" className={classes.dragHandle} disabled={disabled}
                  tabIndex={-1}
                  aria-label={t("rooms.organization.drag", { name: group.name })} {...drag.handlers(source)}
                >
                  <IconGripVertical size={17} aria-hidden="true" />
                </ActionIcon>
                <ActionIcon
                  size="sm" variant="subtle"
                  aria-label={t(isCollapsed ? "rooms.organization.expand" : "rooms.organization.collapse", { name: group.name })}
                  aria-expanded={!isCollapsed}
                  aria-controls={`${titleId}-rooms`}
                  onClick={() => setCollapsed((current) => {
                    const next = new Set(current);
                    if (isCollapsed) next.delete(group.id); else next.add(group.id);
                    return next;
                  })}
                >
                  {isCollapsed ? <IconChevronRight size={17} /> : <IconChevronDown size={17} />}
                </ActionIcon>
                {renaming?.id === group.id ? (
                  <>
                    <VisuallyHidden><Title order={3} id={titleId}>{group.name}</Title></VisuallyHidden>
                    <GroupNameEditor
                      groupId={group.id} originalName={renaming.original} value={renaming.value}
                      duplicate={duplicateRename} disabled={disabled} onValue={(value) => setRenaming({ ...renaming, value })}
                      onCommit={() => finishRename(true)} onCancel={() => finishRename(false)}
                    />
                  </>
                ) : <Title order={3} size="h5" id={titleId} className={classes.groupTitle}>{group.name}</Title>}
                <ItemMenu
                  name={group.name}
                  disabled={disabled}
                  returnFocus={renaming?.id !== group.id}
                  triggerRef={(node) => {
                    if (node) groupActionRefs.current.set(group.id, node);
                    else groupActionRefs.current.delete(group.id);
                  }}
                >
                  <Menu.Item leftSection={<IconPencil size={14} />} onClick={() => setRenaming({ id: group.id, original: group.name, value: group.name })}>
                    {t("rooms.organization.renameItem")}
                  </Menu.Item>
                  <Menu.Item leftSection={<IconArrowUp size={14} />} disabled={index === 0} onClick={() => moveGroup(group.id, group.name, -1)}>
                    {t("rooms.organization.moveUpItem")}
                  </Menu.Item>
                  <Menu.Item leftSection={<IconArrowDown size={14} />} disabled={index === draft.groups.length - 1} onClick={() => moveGroup(group.id, group.name, 1)}>
                    {t("rooms.organization.moveDownItem")}
                  </Menu.Item>
                  <Menu.Divider />
                  <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={() => onChange(deleteOrganizationGroup(draft, group.id))}>
                    {t("rooms.organization.deleteItem")}
                  </Menu.Item>
                </ItemMenu>
              </div>
              {!isCollapsed && (
                <div id={`${titleId}-rooms`}>
                  <RoomList
                    rooms={groupRooms} draft={draft} disabled={disabled}
                    hasUnloadedRooms={draft.rooms.some((room) => room.groupId === group.id && !roomById.has(room.roomId))}
                    visibleUngroupedIds={visibleUngroupedIds} onChange={onChange} dragHandlers={drag.handlers}
                    onMoved={revealGroup}
                    actionRefs={actionRefs} announce={announce}
                  />
                </div>
              )}
            </section>
          );
        })}

        <section className={classes.group} data-group-id={UNGROUPED_DATA_VALUE} aria-labelledby="room-group-ungrouped">
          <div className={classes.groupHeader} data-group-header>
            <span className={classes.handlePlaceholder} aria-hidden="true" />
            <ActionIcon
              size="sm" variant="subtle"
              aria-label={t(collapsed.has(UNGROUPED_DATA_VALUE) ? "rooms.organization.expand" : "rooms.organization.collapse", { name: t("rooms.organization.ungrouped") })}
              aria-expanded={!collapsed.has(UNGROUPED_DATA_VALUE)}
              aria-controls="room-group-ungrouped-rooms"
              onClick={() => setCollapsed((current) => {
                const next = new Set(current);
                if (next.has(UNGROUPED_DATA_VALUE)) next.delete(UNGROUPED_DATA_VALUE); else next.add(UNGROUPED_DATA_VALUE);
                return next;
              })}
            >
              {collapsed.has(UNGROUPED_DATA_VALUE) ? <IconChevronRight size={17} /> : <IconChevronDown size={17} />}
            </ActionIcon>
            <Title order={3} size="h5" id="room-group-ungrouped" className={classes.groupTitle}>{t("rooms.organization.ungrouped")}</Title>
          </div>
          {!collapsed.has(UNGROUPED_DATA_VALUE) && (
            <div id="room-group-ungrouped-rooms">
              <RoomList
                rooms={visible(null)} draft={draft} disabled={disabled}
                hasUnloadedRooms={draft.rooms.some((room) => room.groupId === null && !roomById.has(room.roomId))}
                visibleUngroupedIds={visibleUngroupedIds} onChange={onChange} dragHandlers={drag.handlers}
                onMoved={revealGroup}
                actionRefs={actionRefs} announce={announce}
              />
            </div>
          )}
        </section>
      </div>

      {dirty && (
        <Group justify="flex-end" className={classes.footer}>
          <Button variant="default" onClick={onDiscard} disabled={disabled}>{t("rooms.organization.discard")}</Button>
          <Button onClick={onSave} loading={saving} disabled={invalidNames || adding || renaming !== null || saveBlocked || reloading}>
            {t("rooms.organization.save")}
          </Button>
        </Group>
      )}
    </Stack>
  );
}
