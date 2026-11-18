import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Anchor, Badge, Button, Group, SegmentedControl, Stack, Switch, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconDoor, IconPlus } from "@tabler/icons-react";
import { useAdmin } from "../auth";
import { listRooms } from "../api/rooms";
import ClearableTextInput from "../components/ClearableTextInput";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import RegistryListTable from "../components/RegistryListTable";
import SortHeader from "../components/SortHeader";
import { useRegistryListControls } from "../hooks/useRegistryListControls";
import { isBoolean, isOneOf, useStoredState } from "../hooks/useStoredState";
import { formatDate } from "../utils/formatDate";
import { MAX_ROOM_NAME_LENGTH } from "../utils/roomForm";
import { newRoomPath, roomPath } from "../utils/roomLinks";

const SORT_FIELDS = ["name", "lastActivityAt", "createdAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SCOPES = ["mine", "all"] as const;

const SETTINGS_KEY = "rooms";

/**
 * The Rooms page (`/rooms`): the caller's rooms as a paged table (a room's name opens it). An ADMIN additionally chooses between
 * Mine and All (every room, members or not); everyone can show the archived ones, which stay hidden by default. The Mine/All
 * choice and the archived switch persist like the other view settings.
 */
export default function Rooms() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const [scope, setScope] = useStoredState(`${SETTINGS_KEY}.scope`, "mine", isOneOf(SCOPES));
  const [showArchived, setShowArchived] = useStoredState(`${SETTINGS_KEY}.showArchived`, false, isBoolean);
  const { nameFilter, setNameFilter, debouncedName, nameFilterActive, page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    useRegistryListControls<SortField>({
      settingsKey: SETTINGS_KEY,
      sortFields: SORT_FIELDS,
      initialSortField: "name",
      extraFilterDeps: [scope, showArchived],
    });
  // A regular user only ever has "mine" (the server would answer the same); an ADMIN's "all" omits the member filter.
  const everything = admin && scope === "all";

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["rooms", "list", page, pageSize, sortParam, debouncedName, everything, showArchived],
    queryFn: () =>
      listRooms({
        page,
        pageSize,
        sort: sortParam,
        name: debouncedName || undefined,
        archived: showArchived ? undefined : false,
        member: everything ? undefined : true,
      }),
    placeholderData: keepPreviousData,
  });

  return (
    <Stack gap="md">
      <PageHeader
        title={t("rooms.title")}
        description={t("rooms.intro")}
        actions={
          <Button component={RouterLink} to={newRoomPath} leftSection={<IconPlus size={16} />}>
            {t("rooms.newRoom")}
          </Button>
        }
        toolbar={
          <Group gap="lg">
            {admin && (
              <SegmentedControl
                aria-label={t("rooms.scope.aria")}
                value={scope}
                onChange={(value) => setScope(value as (typeof SCOPES)[number])}
                data={[
                  { value: "mine", label: t("rooms.scope.mine") },
                  { value: "all", label: t("rooms.scope.all") },
                ]}
              />
            )}
            <Switch label={t("rooms.showArchived")} checked={showArchived} onChange={(event) => setShowArchived(event.currentTarget.checked)} />
          </Group>
        }
      />

      <FilterPanel activeFilterCount={nameFilterActive ? 1 : 0} storageKey={SETTINGS_KEY}>
        <ClearableTextInput
          label={t("common.field.name")}
          value={nameFilter}
          onChange={setNameFilter}
          clearLabel={t("common.filter.clearName")}
          maxLength={MAX_ROOM_NAME_LENGTH}
        />
      </FilterPanel>

      <RegistryListTable
        label={t("rooms.title")}
        errorTitle={t("rooms.loadFailed")}
        error={error}
        isError={isError}
        isLoading={isLoading}
        hasData={Boolean(data)}
        rowCount={data?.items.length ?? 0}
        columnCount={5}
        emptyIcon={IconDoor}
        emptyLabel={t("rooms.empty")}
        header={
          <Table.Tr>
            <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("rooms.column.role")}</Table.Th>
            <Table.Th>{t("rooms.column.members")}</Table.Th>
            <SortHeader field="lastActivityAt" label={t("rooms.column.lastActivity")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <SortHeader field="createdAt" label={t("rooms.column.created")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
          </Table.Tr>
        }
        rows={data?.items.map((room) => (
          <Table.Tr key={room.id}>
            <Table.Td>
              <Group gap="xs" wrap="nowrap">
                {/* The name is the way into the room — a real link (the detail-link rule). */}
                <Anchor component={RouterLink} to={roomPath(room.id)} fw={500} size="sm" aria-label={t("rooms.openAria", { name: room.name })}>
                  {room.name}
                </Anchor>
                {room.archived && <Badge color="gray">{t("rooms.archivedBadge")}</Badge>}
              </Group>
              {room.description && (
                <Text size="xs" c="dimmed" lineClamp={1}>
                  {room.description}
                </Text>
              )}
            </Table.Td>
            <Table.Td>
              <Text size="sm" c={room.role ? undefined : "dimmed"}>
                {room.role ? t(`rooms.role.${room.role}`) : "—"}
              </Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">{room.memberCount}</Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">{formatDate(room.lastActivityAt)}</Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">{formatDate(room.createdAt)}</Text>
            </Table.Td>
          </Table.Tr>
        ))}
        total={data?.total ?? 0}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />
    </Stack>
  );
}
