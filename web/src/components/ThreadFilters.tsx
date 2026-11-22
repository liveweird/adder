import { Button, Group, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { RoomWorkflow } from "../api/rooms";
import { useTagCategories } from "../hooks/useTagCategories";
import { tagOptionGroups } from "../utils/threadTags";
import {
  fieldValues,
  filterableFields,
  setFieldValues,
  type ThreadListParams,
} from "../utils/threadListParams";
import FilterMultiSelect from "./FilterMultiSelect";
import FilterPanel from "./FilterPanel";
import MemberFilterSelect from "./MemberFilterSelect";
import RoomHashtagSelect from "./RoomHashtagSelect";

/**
 * The thread list's filter panel (inside the shared `FilterPanel`, stacked: the room view's left rail is narrow): the statuses (with the
 * workflow's thread counts), the room's hashtags (`RoomHashtagSelect`, searched on the server), the curated tags (the registry, grouped by category, 1.2 D3), the owner ("Mine" is `owner=me`, "Mentions me" is `mention=me`, sent as `mentionsMe=true` (a mention of the caller by name, through their team, of everyone, or of the watchers they are in), "Watching" is `watching=me`, "Unread" is `unread=me` (sent as `unread=true`, a member's own read state: not offered to an ADMIN outside the room), the picker any member) and one control per ACTIVE SELECT, MULTI_SELECT or
 * USER field of the room's workflow. Every control writes the URL through `update`; the panel only draws what the URL says.
 * "Clear filters" is the hook's `clear`: the panel's filters and the page only, the scope and the order stay.
 */
export default function ThreadFilters({
  roomId,
  me,
  member,
  workflow,
  params,
  update,
  clear,
  activeCount,
}: {
  roomId: number;
  /** The session's user id; without one "Mine" has nothing to send and is disabled. */
  me: number | null;
  /** The caller is a member of the room: an ADMIN outside it has no read state, so "Unread" is not offered (the server would answer an empty page). */
  member: boolean;
  workflow: RoomWorkflow | undefined;
  params: ThreadListParams;
  update: (patch: Partial<ThreadListParams>) => void;
  clear: () => void;
  activeCount: number;
}) {
  const { t } = useTranslation();
  // The registry (one cached read, shared with the tag editor): the Tags control offers its live tags grouped by category; a tag already in
  // the URL that is no longer there (retired, or the registry not read yet) keeps its pill under its own name.
  const categories = useTagCategories();
  const counts = new Map((workflow?.statuses ?? []).map((status) => [status.key, status.threadCount]));
  const statusOptions = (workflow?.statuses ?? []).map((status) => ({ value: status.key, label: status.label }));
  const mineInUrl = params.owner === "me";
  const mine = mineInUrl && me !== null;
  const mentioned = params.mention && me !== null;
  return (
    <FilterPanel activeFilterCount={activeCount} storageKey="roomThreads">
      <Stack gap="xs" w="100%">
        <FilterMultiSelect
          label={t("threads.filters.status")}
          emptyLabel={t("threads.filters.all")}
          w="100%"
          data={statusOptions}
          value={params.status}
          onChange={(status) => update({ status })}
          renderOption={({ option }) => (
            <Group justify="space-between" gap="xs" w="100%" wrap="nowrap">
              <span>{option.label}</span>
              <Text span size="xs" c="dimmed">{`(${counts.get(option.value) ?? 0})`}</Text>
            </Group>
          )}
        />
        <RoomHashtagSelect
          roomId={roomId}
          label={t("threads.filters.hashtags")}
          emptyLabel={t("threads.filters.all")}
          value={params.tag}
          onChange={(tag) => update({ tag })}
        />
        <FilterMultiSelect
          label={t("threads.filters.tags")}
          emptyLabel={t("threads.filters.all")}
          w="100%"
          data={tagOptionGroups(categories.data ?? [])}
          value={params.threadTag}
          onChange={(threadTag) => update({ threadTag })}
          nothingFoundMessage={t("threads.filters.noTags")}
        />
        <Stack gap={4}>
          <Group gap={4} wrap="wrap">
            <Button
              size="compact-xs"
              variant={mine ? "light" : "default"}
              aria-pressed={mine}
              disabled={me === null}
              onClick={() => update({ owner: mineInUrl ? null : "me" })}
            >
              {t("threads.filters.mine")}
            </Button>
            <Button
              size="compact-xs"
              variant={mentioned ? "light" : "default"}
              aria-pressed={mentioned}
              disabled={me === null}
              onClick={() => update({ mention: !params.mention })}
            >
              {t("threads.filters.mentionsMe")}
            </Button>
            <Button size="compact-xs" variant={params.watching ? "light" : "default"} aria-pressed={params.watching} onClick={() => update({ watching: !params.watching })}>
              {t("threads.filters.watching")}
            </Button>
            {member && (
              <Button size="compact-xs" variant={params.unread ? "light" : "default"} aria-pressed={params.unread} onClick={() => update({ unread: !params.unread })}>
                {t("threads.filters.unread")}
              </Button>
            )}
          </Group>
          <MemberFilterSelect
            roomId={roomId}
            label={t("threads.filters.owner")}
            value={mineInUrl ? null : params.owner}
            onChange={(owner) => update({ owner })}
          />
        </Stack>
        {filterableFields(workflow).map((field) =>
          field.type === "USER" ? (
            <MemberFilterSelect
              key={field.key}
              roomId={roomId}
              label={field.label}
              value={fieldValues(params.field, field.key)[0] ?? null}
              onChange={(value) => update({ field: setFieldValues(params.field, field.key, value === null ? [] : [value]) })}
            />
          ) : (
            <FilterMultiSelect
              key={field.key}
              label={field.label}
              emptyLabel={t("threads.filters.all")}
              w="100%"
              data={field.options.map((option) => ({ value: option.key, label: option.label }))}
              value={fieldValues(params.field, field.key)}
              onChange={(values) => update({ field: setFieldValues(params.field, field.key, values) })}
            />
          ),
        )}
        {activeCount > 0 && (
          <Button size="compact-sm" variant="subtle" onClick={clear} style={{ alignSelf: "flex-start" }}>
            {t("threads.filters.clear")}
          </Button>
        )}
      </Stack>
    </FilterPanel>
  );
}
