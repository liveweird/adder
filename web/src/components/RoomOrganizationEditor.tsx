import { useRef, useState, type RefObject } from "react";
import { ActionIcon, Badge, Button, Group, Select, Stack, Text, TextInput, Title, VisuallyHidden } from "@mantine/core";
import { IconArrowDown, IconArrowUp, IconPlus, IconTrash } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { RoomListItem } from "../api/rooms";
import {
  deleteOrganizationGroup,
  moveOrganizationGroup,
  moveOrganizationRoom,
  moveRoomToGroup,
  MAX_ROOM_PLACEMENTS,
  type RoomOrganizationDraft,
} from "../utils/roomOrganizationDraft";
import classes from "./RoomOrganizationEditor.module.css";

const MAX_ROOM_GROUPS = 50;
const MAX_ROOM_GROUP_NAME_LENGTH = 60;

const UNGROUPED_VALUE = "__ungrouped__";

function MoveButtons({ name, first, last, disabled, onMove }: {
  name: string;
  first: boolean;
  last: boolean;
  disabled: boolean;
  onMove: (delta: -1 | 1) => void;
}) {
  const { t } = useTranslation();
  return (
    <Group gap={4} wrap="nowrap">
      <ActionIcon aria-label={t("rooms.organization.moveUp", { name })} disabled={disabled || first} onClick={() => onMove(-1)}>
        <IconArrowUp size={16} aria-hidden="true" />
      </ActionIcon>
      <ActionIcon aria-label={t("rooms.organization.moveDown", { name })} disabled={disabled || last} onClick={() => onMove(1)}>
        <IconArrowDown size={16} aria-hidden="true" />
      </ActionIcon>
    </Group>
  );
}

function RoomRows({ rooms, draft, groupId, onChange, announce, disabled, hasUnloadedRooms, groupControls }: {
  rooms: readonly RoomListItem[];
  draft: RoomOrganizationDraft;
  groupId: string | null;
  onChange: (draft: RoomOrganizationDraft) => void;
  announce: (message: string) => void;
  disabled: boolean;
  hasUnloadedRooms: boolean;
  groupControls: RefObject<Map<number, HTMLInputElement>>;
}) {
  const { t } = useTranslation();
  const placedIds = new Set(draft.rooms.map((room) => room.roomId));
  const roomIds = rooms.map((room) => room.id);
  const available = MAX_ROOM_PLACEMENTS - draft.rooms.length;
  const reorderLimit = roomIds.filter((id) => placedIds.has(id)).length + available;
  const options = [{ value: UNGROUPED_VALUE, label: t("rooms.organization.ungrouped") }, ...draft.groups.map((group) => ({ value: group.id, label: group.name }))];
  if (rooms.length === 0) return (
    <Text size="sm" c="dimmed" className={classes.empty}>
      {hasUnloadedRooms ? t("rooms.organization.roomsNotLoaded") : t("rooms.organization.emptyGroup")}
    </Text>
  );
  return (
    <ul className={classes.roomList} role="list">
      {rooms.map((room, index) => (
        <li key={room.id} className={classes.roomItem}>
          <div className={classes.roomRow}>
            <Group gap="xs" wrap="wrap" className={classes.roomName}>
              <Text fw={600} size="sm" lineClamp={1}>{room.name}</Text>
              {room.archived && <Badge size="xs">{t("rooms.archivedBadge")}</Badge>}
            </Group>
            <Select
              ref={(node) => {
                if (!node) return;
                const controls = groupControls.current;
                controls.set(room.id, node);
                return () => {
                  if (controls.get(room.id) === node) controls.delete(room.id);
                };
              }}
              className={classes.roomGroup}
              size="xs"
              aria-label={t("rooms.organization.moveRoom", { name: room.name })}
              data={options}
              value={groupId ?? UNGROUPED_VALUE}
              disabled={disabled || (!placedIds.has(room.id) && available === 0)}
              onChange={(value) => {
                onChange(moveRoomToGroup(draft, room.id, value === UNGROUPED_VALUE ? null : value));
                announce(t("rooms.organization.changed"));
                window.requestAnimationFrame(() => groupControls.current.get(room.id)?.focus());
              }}
              allowDeselect={false}
            />
            <MoveButtons
              name={room.name}
              first={index === 0 || index >= reorderLimit}
              last={index === rooms.length - 1 || index + 1 >= reorderLimit}
              disabled={disabled}
              onMove={(delta) => {
                onChange(moveOrganizationRoom(draft, room.id, delta, roomIds));
                announce(t("rooms.organization.changed"));
              }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

export default function RoomOrganizationEditor({ rooms, draft, onChange, onSave, onDiscard, saving, saveBlocked = false, dirty }: {
  rooms: readonly RoomListItem[];
  draft: RoomOrganizationDraft;
  onChange: (draft: RoomOrganizationDraft) => void;
  onSave: () => void;
  onDiscard: () => void;
  saving: boolean;
  saveBlocked?: boolean;
  dirty: boolean;
}) {
  const { t } = useTranslation();
  const [newName, setNewName] = useState("");
  const [announcement, setAnnouncement] = useState("");
  const addRef = useRef<HTMLInputElement>(null);
  const groupControls = useRef(new Map<number, HTMLInputElement>());
  const roomById = new Map(rooms.map((room) => [room.id, room]));
  const placementIds = new Set(draft.rooms.map(({ roomId }) => roomId));
  const visible = (groupId: string | null) => {
    const placed = draft.rooms
    .filter((room) => room.groupId === groupId)
    .map(({ roomId }) => roomById.get(roomId))
    .filter((room): room is RoomListItem => room !== undefined);
    if (groupId !== null) return placed;
    return [...placed, ...rooms.filter((room) => !placementIds.has(room.id))];
  };
  const duplicateName = (name: string, ownId?: string) => draft.groups.some((group) => (
    group.id !== ownId && group.name.trim().localeCompare(name.trim(), undefined, { sensitivity: "accent" }) === 0
  ));
  const invalidNames = draft.groups.some((group) => group.name.trim() === "" || duplicateName(group.name, group.id));
  const announce = (message: string) => {
    setAnnouncement("");
    window.setTimeout(() => setAnnouncement(message), 0);
  };

  return (
    <Stack gap="lg" className={classes.editor}>
      <VisuallyHidden role="status" aria-live="polite">{announcement}</VisuallyHidden>
      <Group align="flex-end" wrap="wrap">
        <TextInput
          ref={addRef}
          label={t("rooms.organization.groupName")}
          placeholder={t("rooms.organization.newGroup")}
          value={newName}
          onChange={(event) => setNewName(event.currentTarget.value)}
          maxLength={MAX_ROOM_GROUP_NAME_LENGTH}
          disabled={saving || draft.groups.length >= MAX_ROOM_GROUPS}
          error={newName.trim() !== "" && duplicateName(newName) ? t("rooms.organization.duplicateName") : undefined}
        />
        <Button
          variant="default"
          leftSection={<IconPlus size={16} aria-hidden="true" />}
          disabled={saving || newName.trim() === "" || duplicateName(newName) || draft.groups.length >= MAX_ROOM_GROUPS}
          onClick={() => {
            const name = newName.trim();
            if (name === "") return;
            onChange({ ...draft, groups: [...draft.groups, { id: crypto.randomUUID(), name }] });
            setNewName("");
            addRef.current?.focus();
          }}
        >
          {t("rooms.organization.addGroup")}
        </Button>
        {draft.groups.length >= MAX_ROOM_GROUPS && <Text size="sm" c="dimmed">{t("rooms.organization.limit", { count: MAX_ROOM_GROUPS })}</Text>}
      </Group>
      {draft.rooms.length + rooms.filter((room) => !placementIds.has(room.id)).length >= MAX_ROOM_PLACEMENTS && (
        <Text size="sm" c="dimmed">{t("rooms.organization.placementLimit", { count: MAX_ROOM_PLACEMENTS })}</Text>
      )}

      {draft.groups.map((group, index) => (
        <section key={group.id} className={classes.group} aria-labelledby={`room-group-${group.id}`}>
          <VisuallyHidden><Title order={3} id={`room-group-${group.id}`}>{group.name}</Title></VisuallyHidden>
          <Group justify="space-between" wrap="wrap" className={classes.groupHeader}>
            <TextInput
              className={classes.groupName}
              value={group.name}
              maxLength={MAX_ROOM_GROUP_NAME_LENGTH}
              disabled={saving}
              error={duplicateName(group.name, group.id) ? t("rooms.organization.duplicateName") : undefined}
              aria-label={t("rooms.organization.rename", { name: group.name })}
              onChange={(event) => onChange({
                ...draft,
                groups: draft.groups.map((candidate) => candidate.id === group.id ? { ...candidate, name: event.currentTarget.value } : candidate),
              })}
            />
            <Group gap={4} wrap="nowrap">
              <MoveButtons
                name={group.name}
                first={index === 0}
                last={index === draft.groups.length - 1}
                disabled={saving}
                onMove={(delta) => {
                  onChange(moveOrganizationGroup(draft, group.id, delta));
                  announce(t("rooms.organization.changed"));
                }}
              />
              <ActionIcon
                color="red"
                variant="subtle"
                aria-label={t("rooms.organization.delete", { name: group.name })}
                title={t("rooms.organization.deleteHint")}
                disabled={saving}
                onClick={() => onChange(deleteOrganizationGroup(draft, group.id))}
              >
                <IconTrash size={16} aria-hidden="true" />
              </ActionIcon>
            </Group>
          </Group>
          <RoomRows
            rooms={visible(group.id)}
            draft={draft}
            groupId={group.id}
            onChange={onChange}
            announce={announce}
            disabled={saving}
            groupControls={groupControls}
            hasUnloadedRooms={draft.rooms.some((room) => room.groupId === group.id && !roomById.has(room.roomId))}
          />
        </section>
      ))}

      <section className={classes.group} aria-labelledby="room-group-ungrouped">
        <div className={classes.groupHeader}>
          <Title order={3} size="h4" id="room-group-ungrouped">{t("rooms.organization.ungrouped")}</Title>
        </div>
        <RoomRows
          rooms={visible(null)}
          draft={draft}
          groupId={null}
          onChange={onChange}
          announce={announce}
          disabled={saving}
          groupControls={groupControls}
          hasUnloadedRooms={draft.rooms.some((room) => room.groupId === null && !roomById.has(room.roomId))}
        />
      </section>

      <Group justify="flex-end" className={classes.footer}>
        <Button variant="default" onClick={onDiscard} disabled={!dirty || saving}>{t("rooms.organization.discard")}</Button>
        <Button onClick={onSave} loading={saving} disabled={!dirty || invalidNames || saveBlocked}>
          {t("rooms.organization.save")}
        </Button>
      </Group>
    </Stack>
  );
}
