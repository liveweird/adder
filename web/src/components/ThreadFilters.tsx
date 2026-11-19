import { Button, Group, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { RoomWorkflow } from "../api/rooms";
import {
  fieldValues,
  filterableFields,
  setFieldValues,
  type ThreadListParams,
} from "../utils/threadListParams";
import FilterMultiSelect from "./FilterMultiSelect";
import FilterPanel from "./FilterPanel";
import MemberFilterSelect from "./MemberFilterSelect";

/**
 * The thread list's filter panel (inside the shared `FilterPanel`, stacked: the room view's left rail is narrow): the statuses (with the
 * workflow's thread counts), the owner ("Mine" is `owner=me`, the picker any member) and one control per ACTIVE SELECT, MULTI_SELECT or
 * USER field of the room's workflow. Every control writes the URL through `update`; the panel only draws what the URL says.
 * "Clear filters" is the hook's `clear`: the panel's filters and the page only, the scope and the order stay.
 */
export default function ThreadFilters({
  roomId,
  me,
  workflow,
  params,
  update,
  clear,
  activeCount,
}: {
  roomId: number;
  /** The session's user id; without one "Mine" has nothing to send and is disabled. */
  me: number | null;
  workflow: RoomWorkflow | undefined;
  params: ThreadListParams;
  update: (patch: Partial<ThreadListParams>) => void;
  clear: () => void;
  activeCount: number;
}) {
  const { t } = useTranslation();
  const counts = new Map((workflow?.statuses ?? []).map((status) => [status.key, status.threadCount]));
  const statusOptions = (workflow?.statuses ?? []).map((status) => ({ value: status.key, label: status.label }));
  const mineInUrl = params.owner === "me";
  const mine = mineInUrl && me !== null;
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
        <Stack gap={4}>
          <Button
            size="compact-xs"
            variant={mine ? "light" : "default"}
            aria-pressed={mine}
            disabled={me === null}
            onClick={() => update({ owner: mineInUrl ? null : "me" })}
            style={{ alignSelf: "flex-start" }}
          >
            {t("threads.filters.mine")}
          </Button>
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
