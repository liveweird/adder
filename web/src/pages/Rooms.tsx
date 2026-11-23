import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Anchor, Avatar, Badge, Box, Button, Group, SegmentedControl, Stack, Switch, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconDoor, IconPlus } from "@tabler/icons-react";
import { listRooms } from "../api/rooms";
import ClearableTextInput from "../components/ClearableTextInput";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import RegistryListTable from "../components/RegistryListTable";
import RoomAccessBadge from "../components/RoomAccessBadge";
import SortHeader from "../components/SortHeader";
import { useRegistryListControls } from "../hooks/useRegistryListControls";
import { isBoolean, isOneOf, useStoredState } from "../hooks/useStoredState";
import { browserTimeZone, formatDate } from "../utils/formatDate";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { MAX_ROOM_NAME_LENGTH } from "../utils/roomForm";
import { newRoomPath, roomPath } from "../utils/roomLinks";
import { roomKeys } from "../utils/roomKeys";
import { initialsOf } from "../utils/text";
import classes from "./Rooms.module.css";

const SORT_FIELDS = ["name", "lastActivityAt", "createdAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SCOPES = ["mine", "all"] as const;

const SETTINGS_KEY = "rooms";

/**
 * The Rooms page (`/rooms`): the discoverable room directory. Everyone chooses between Joined and Browse; Browse includes public and
 * restricted-visible rooms plus the caller's joined hidden rooms. Everyone can show archived rooms, which stay hidden by default. The scope
 * choice and the archived switch persist like the other view settings.
 */
export default function Rooms() {
  const { t } = useTranslation();
  const [scope, setScope] = useStoredState(`${SETTINGS_KEY}.scope`, "all", isOneOf(SCOPES));
  const [showArchived, setShowArchived] = useStoredState(`${SETTINGS_KEY}.showArchived`, false, isBoolean);
  const { nameFilter, setNameFilter, debouncedName, nameFilterActive, page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    useRegistryListControls<SortField>({
      settingsKey: SETTINGS_KEY,
      sortFields: SORT_FIELDS,
      initialSortField: "name",
      extraFilterDeps: [scope, showArchived],
    });
  const joined = scope === "mine";

  const { data, isLoading, isError, error } = useQuery({
    queryKey: [...roomKeys.list, page, pageSize, sortParam, debouncedName, joined, showArchived],
    queryFn: () =>
      listRooms({
        page,
        pageSize,
        sort: sortParam,
        name: debouncedName || undefined,
        archived: showArchived ? undefined : false,
        member: joined ? true : undefined,
      }),
    placeholderData: keepPreviousData,
  });

  return (
    <Stack gap="md" maw={CONTENT_MAX_WIDTH} className={classes.page}>
      <PageHeader
        title={t("rooms.title")}
        actions={
          <Button component={RouterLink} to={newRoomPath} leftSection={<IconPlus size={16} />} className={classes.newRoom}>
            {t("rooms.newRoom")}
          </Button>
        }
        toolbar={
          <Group gap="lg" className={classes.scopeBar}>
            <SegmentedControl
              aria-label={t("rooms.scope.aria")}
              value={scope}
              onChange={(value) => setScope(value as (typeof SCOPES)[number])}
              data={[
                { value: "all", label: t("rooms.scope.all") },
                { value: "mine", label: t("rooms.scope.mine") },
              ]}
            />
            <Switch label={t("rooms.showArchived")} checked={showArchived} onChange={(event) => setShowArchived(event.currentTarget.checked)} />
          </Group>
        }
      />

      <Box className={classes.directory}>
        <Stack gap="md">
          <FilterPanel activeFilterCount={nameFilterActive ? 1 : 0} storageKey={SETTINGS_KEY}>
            <ClearableTextInput
              label={t("common.field.name")}
              value={nameFilter}
              onChange={setNameFilter}
              clearLabel={t("common.filter.clearName")}
              maxLength={MAX_ROOM_NAME_LENGTH}
            />
          </FilterPanel>

          <Box className={classes.tableRegion}>
            <RegistryListTable
              label={t("rooms.title")}
              errorTitle={t("rooms.loadFailed")}
              error={error}
              isError={isError}
              isLoading={isLoading}
              hasData={Boolean(data)}
              rowCount={data?.items.length ?? 0}
              columnCount={4}
              emptyIcon={IconDoor}
              emptyLabel={t("rooms.empty")}
              header={
                <Table.Tr role="row">
                  <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
                  <Table.Th className={classes.membersHeader}>{t("rooms.column.members")}</Table.Th>
                  <SortHeader field="lastActivityAt" label={t("rooms.column.lastActivity")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
                  <SortHeader field="createdAt" label={t("rooms.column.created")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
                </Table.Tr>
              }
              rows={data?.items.map((room) => (
                <Table.Tr key={room.id} role="row" className={classes.roomRow}>
                  <Table.Td role="cell" className={classes.identityCell}>
                    <Group gap="sm" wrap="nowrap" align="flex-start">
                      <Avatar size={36} radius="md" variant="light" color="gray" className={classes.avatar} aria-hidden="true">
                        {initialsOf(room.name)}
                      </Avatar>
                      <Box className={classes.identity}>
                        <Group gap={6} wrap="wrap" className={classes.nameLine}>
                          {/* The name is the way into the room — a real link (the detail-link rule). */}
                          <Anchor
                            component={RouterLink}
                            to={roomPath(room.id)}
                            fw={600}
                            size="sm"
                            aria-label={t("rooms.openAria", { name: room.name })}
                            className={classes.roomLink}
                          >
                            {room.name}
                          </Anchor>
                          {room.role && <Badge size="xs">{t(`rooms.role.${room.role}`)}</Badge>}
                          <RoomAccessBadge accessType={room.accessType} size="xs" />
                          {room.archived && <Badge size="xs">{t("rooms.archivedBadge")}</Badge>}
                        </Group>
                        {room.description && (
                          <Text size="xs" c="dimmed" lineClamp={1} className={classes.description}>
                            {room.description}
                          </Text>
                        )}
                        {!room.role && (
                          <Text size="xs" c="dimmed" className={classes.noRole}>
                            {t("rooms.column.role")}: <Text component="span" inherit>—</Text>
                          </Text>
                        )}
                      </Box>
                    </Group>
                  </Table.Td>
                  <Table.Td role="cell" className={classes.metaCell}>
                    <Text component="span" size="xs" c="dimmed" className={classes.mobileLabel}>
                      {t("rooms.column.members")}
                    </Text>
                    <Text size="sm" className={classes.numeric}>{room.memberCount}</Text>
                  </Table.Td>
                  <Table.Td role="cell" className={classes.metaCell}>
                    <Text component="span" size="xs" c="dimmed" className={classes.mobileLabel}>
                      {t("rooms.column.lastActivity")}
                    </Text>
                    <Text size="sm" className={classes.date}>{formatDate(room.lastActivityAt, "—", browserTimeZone())}</Text>
                  </Table.Td>
                  <Table.Td role="cell" className={classes.metaCell}>
                    <Text component="span" size="xs" c="dimmed" className={classes.mobileLabel}>
                      {t("rooms.column.created")}
                    </Text>
                    <Text size="sm" className={classes.date}>{formatDate(room.createdAt, "—", browserTimeZone())}</Text>
                  </Table.Td>
                </Table.Tr>
              ))}
              total={data?.total ?? 0}
              page={page}
              pageSize={pageSize}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
            />
          </Box>
        </Stack>
      </Box>
    </Stack>
  );
}
