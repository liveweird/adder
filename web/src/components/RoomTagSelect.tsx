import { useState } from "react";
import { Group, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { listRoomTags } from "../api/threads";
import { FILTER_DEBOUNCE_MS, OPTION_PAGE_SIZE } from "../hooks/usePagedSort";
import { roomKeys } from "../utils/roomKeys";
import { MAX_TAG_VALUES } from "../utils/threadListParams";
import ErrorAlert from "./ErrorAlert";
import FilterMultiSelect from "./FilterMultiSelect";

/** The server's cap on the tag list's `tag` search term. */
const TAG_TERM_MAX_LENGTH = 50;

/** What the box holds as a search term: no leading hash (a tag is stored without it), at most the server's limit of characters. */
const searchTerm = (typed: string) => [...typed.trim().replace(/^#+/, "").trim()].slice(0, TAG_TERM_MAX_LENGTH).join("");

/**
 * The hashtag filter: a `FilterMultiSelect` over one room's tags (`GET /rooms/{id}/tags?tag=`, the most used first, a reader's view),
 * searched on the server with the debounced term (the `SearchPicker` mechanics). The caller holds the picked values (the thread list's `tag`
 * URL key): a pick is the tag as the list returned it (folded), a value from a link is the spelling typed in a message, and a value the
 * current search does not return is still an option (labelled by itself), so its pill keeps its name. At most 10 values, the server's limit.
 */
export default function RoomTagSelect({
  roomId,
  label,
  emptyLabel,
  value,
  onChange,
}: {
  roomId: number;
  label: string;
  emptyLabel: string;
  value: string[];
  onChange: (next: string[]) => void;
}) {
  const { t } = useTranslation();
  const [searchValue, setSearchValue] = useState("");
  const term = searchTerm(searchValue);
  const [debounced] = useDebouncedValue(term, FILTER_DEBOUNCE_MS);
  // Under the room's thread-list prefix, so a live hint that refreshes the list (a message with a tag) re-reads the options.
  const results = useQuery({
    queryKey: [...roomKeys.threadTags(roomId), debounced],
    queryFn: () => listRoomTags(roomId, { page: 1, pageSize: OPTION_PAGE_SIZE, tag: debounced || undefined }),
    // The previous term's rows bridge a new search, never another room's (the key's third part is the room).
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[2] === roomId ? previous : undefined),
  });
  const counts = new Map((results.data?.items ?? []).map((row) => [row.tag, row.threadCount]));
  // A picked value the search does not return stays an option, unless it is a spelling of a returned (folded) tag: `Outage` beside `outage`.
  const values = [...counts.keys(), ...value.filter((tag) => !counts.has(tag) && !counts.has(tag.toLowerCase()))];
  const loading = results.isLoading || results.isPlaceholderData;
  return (
    <>
      <FilterMultiSelect
        label={label}
        emptyLabel={emptyLabel}
        w="100%"
        data={values.map((tag) => ({ value: tag, label: `#${tag}` }))}
        value={value}
        onChange={onChange}
        valueLabel={(tag) => `#${tag}`}
        maxValues={MAX_TAG_VALUES}
        searchValue={searchValue}
        onSearchChange={setSearchValue}
        nothingFoundMessage={loading || results.isError || term !== debounced ? undefined : t("threads.filters.noTags")}
        aria-busy={loading}
        renderOption={({ option }) => (
          <Group justify="space-between" gap="xs" w="100%" wrap="nowrap">
            <span>{option.label}</span>
            {counts.has(option.value) && (
              <Text span size="xs" c="dimmed">{`(${counts.get(option.value)})`}</Text>
            )}
          </Group>
        )}
      />
      {results.isError && <ErrorAlert error={results.error} />}
    </>
  );
}
