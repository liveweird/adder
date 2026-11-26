import { forwardRef, useImperativeHandle, useMemo, useRef, useState } from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { ActionIcon, Button, Code, Divider, Group, Paper, Popover, SimpleGrid, Stack, Text, ThemeIcon, UnstyledButton } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { IconAdjustmentsHorizontal, IconArrowRight, IconBulb, IconInfoCircle } from "@tabler/icons-react";
import { getRoom, getRoomWorkflow, listRooms, type RoomWorkflow } from "../api/rooms";
import { listRoomHashtags } from "../api/threads";
import { searchUserDirectory } from "../api/users";
import {
  contextualSearchToken,
  operatorCorrection,
  roomSelectionOperand,
  SEARCH_ASSISTANT_KEY,
  searchLookupTerm,
  searchOperatorSuggestions,
  type SearchTokenContext,
} from "../utils/searchQueryEditor";
import { roomKeys } from "../utils/roomKeys";
import EmojiButton from "./EmojiButton";
import classes from "./SearchQueryAssistant.module.css";

const SUGGESTION_PAGE_SIZE = 8;
const LOOKUP_DEBOUNCE_MS = 300;
export type SearchQueryAssistantHandle = { focusFirstSuggestion: () => boolean };

type Suggestion = { id: string; insert: string; label: string; description: string };

type Props = {
  query: string;
  caret: number;
  onInsert: (start: number, end: number, replacement: string) => void;
  roomId?: number;
  statuses?: RoomWorkflow["statuses"];
};

const normalizedValue = (value: string) => value.replace(/^"/u, "").replace(/"$/u, "").trim();
const hashtagOperand = (tag: string) => /[\s"\\]/u.test(tag) ? `"${tag.replace(/["\\]/gu, "\\$&")}"` : tag;
const sameRoomName = (left: string, right: string) => left.localeCompare(right, undefined, { sensitivity: "accent" }) === 0;
const isPersonOperator = (operator: SearchTokenContext["operator"]) => operator === "from" || operator === "with" || operator === "owner";
const isRoomFilterOperator = (operator: SearchTokenContext["operator"]) => operator === "status" || operator === "hashtag";
const isEntityOperator = (operator: SearchTokenContext["operator"]) => operator === "in" || isPersonOperator(operator);

function resolvedRoomId(
  fixedRoomId: number | undefined,
  selectedId: number | null,
  selectedRoom: { id: number; role: unknown } | undefined,
  exactNamedRooms: readonly { id: number }[],
): number | undefined {
  if (fixedRoomId !== undefined) return fixedRoomId;
  if (selectedId !== null) return selectedRoom !== undefined && selectedRoom.role !== null ? selectedRoom.id : undefined;
  return exactNamedRooms.length === 1 ? exactNamedRooms[0].id : undefined;
}

const needsRoomHint = (fixedRoomId: number | undefined, roomFilterActive: boolean, selectedRoomId: number | undefined, resolving: boolean) =>
  fixedRoomId === undefined && roomFilterActive && selectedRoomId === undefined && !resolving;

function makeSuggestions({
  token,
  rooms,
  people,
  statuses,
  hashtags,
  entityLookupReady,
  selectedRoomId,
  filterLookup,
  filterLookupSettled,
  t,
}: {
  token: SearchTokenContext;
  rooms: readonly { id: number; name: string }[];
  people: readonly { id: number; name: string; email: string }[];
  statuses: RoomWorkflow["statuses"] | undefined;
  hashtags: readonly { tag: string; threadCount: number }[];
  entityLookupReady: boolean;
  selectedRoomId: number | undefined;
  filterLookup: string;
  filterLookupSettled: boolean;
  t: TFunction;
}): Suggestion[] {
  if (token.operator === "in" && entityLookupReady) {
    return rooms.map((room) => ({
      id: `room-${room.id}`,
      insert: `in:#${room.id}`,
      label: room.name,
      description: t("search.syntax.suggestions.room"),
    }));
  }
  if ((token.operator === "from" || token.operator === "with" || token.operator === "owner") && entityLookupReady) {
    return people.map((person) => ({
      id: `person-${person.id}`,
      insert: `${token.operator}:#${person.id}`,
      label: person.name,
      description: person.email,
    }));
  }
  if (token.operator === "status" && selectedRoomId !== undefined) {
    const value = filterLookup.toLocaleLowerCase();
    return (statuses ?? [])
      .filter((status) => status.key.toLocaleLowerCase().includes(value) || status.label.toLocaleLowerCase().includes(value))
      .slice(0, SUGGESTION_PAGE_SIZE)
      .map((status) => ({
        id: `status-${status.key}`,
        insert: `status:${status.key}`,
        label: status.label,
        description: t("search.syntax.suggestions.status", { key: status.key }),
      }));
  }
  if (token.operator === "hashtag" && selectedRoomId !== undefined && filterLookupSettled) {
    return hashtags.map((hashtag) => ({
      id: `hashtag-${hashtag.tag}`,
      insert: `hashtag:${hashtagOperand(hashtag.tag)}`,
      label: hashtag.tag,
      description: t("search.syntax.suggestions.hashtag", { count: hashtag.threadCount }),
    }));
  }
  if (token.text === "" || token.value !== "") return [];
  return searchOperatorSuggestions(token.text).map((item) => ({
    ...item,
    label: item.insert,
    description: t(`search.syntax.operators.${item.id}`),
  }));
}

const SearchQueryAssistant = forwardRef<SearchQueryAssistantHandle, Props>(function SearchQueryAssistant(
  { query, caret, onInsert, roomId: fixedRoomId, statuses: fixedStatuses },
  ref,
) {
  const { t } = useTranslation();
  const [helpOpened, setHelpOpened] = useState(false);
  const suggestionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const token = useMemo(() => contextualSearchToken(query, caret), [query, caret]);
  const correction = useMemo(() => operatorCorrection(query, caret), [query, caret]);
  const lookup = searchLookupTerm(token.value);
  const [debouncedLookup] = useDebouncedValue(lookup ?? "", LOOKUP_DEBOUNCE_MS);
  const lookupSettled = lookup !== null && lookup === debouncedLookup;
  const lookingForRoom = token.operator === "in";
  const lookingForPerson = isPersonOperator(token.operator);
  const lookingForStatus = token.operator === "status";
  const lookingForHashtag = token.operator === "hashtag";
  const roomFilterActive = isRoomFilterOperator(token.operator);
  const globalRoomFilterActive = fixedRoomId === undefined && roomFilterActive;
  const entityLookupEnabled = isEntityOperator(token.operator) && lookup !== null;
  const filterLookup = normalizedValue(token.value);
  const hashtagLookup = filterLookup.replace(/^#/u, "").replace(/\\(["\\])/gu, "$1");
  const boundedFilterLookup = [...hashtagLookup].length <= 50 ? hashtagLookup : null;
  const [debouncedFilterLookup] = useDebouncedValue(boundedFilterLookup ?? "", LOOKUP_DEBOUNCE_MS);
  const filterLookupSettled = boundedFilterLookup !== null && boundedFilterLookup === debouncedFilterLookup;
  const roomOperand = fixedRoomId === undefined ? roomSelectionOperand(query) : null;
  const selectedIdMatch = roomOperand?.match(/^#([1-9]\d*)$/u);
  const parsedSelectedId = selectedIdMatch ? Number(selectedIdMatch[1]) : null;
  const selectedId = parsedSelectedId !== null && parsedSelectedId <= 2_147_483_647 ? parsedSelectedId : null;
  const selectedName = roomOperand !== null && selectedId === null ? searchLookupTerm(roomOperand) : null;

  const rooms = useQuery({
    queryKey: [...SEARCH_ASSISTANT_KEY, "rooms", debouncedLookup],
    queryFn: () => listRooms({ page: 1, pageSize: SUGGESTION_PAGE_SIZE, sort: "name", name: debouncedLookup, member: true }),
    enabled: entityLookupEnabled && lookupSettled && lookingForRoom,
  });
  const people = useQuery({
    queryKey: [...SEARCH_ASSISTANT_KEY, "people", debouncedLookup],
    queryFn: () => searchUserDirectory({ q: debouncedLookup, page: 1, pageSize: SUGGESTION_PAGE_SIZE, sort: "name" }),
    enabled: entityLookupEnabled && lookupSettled && lookingForPerson,
  });
  const selectedRoom = useQuery({
    queryKey: roomKeys.detail(selectedId),
    queryFn: () => getRoom(selectedId!),
    enabled: globalRoomFilterActive && selectedId !== null,
  });
  const selectedRooms = useQuery({
    queryKey: [...SEARCH_ASSISTANT_KEY, "selected-room", selectedName],
    queryFn: () => listRooms({ page: 1, pageSize: SUGGESTION_PAGE_SIZE, sort: "name", name: selectedName!, member: true }),
    enabled: globalRoomFilterActive && selectedName !== null,
  });
  const exactNamedRooms = (selectedRooms.data?.items ?? []).filter((room) => selectedName !== null && sameRoomName(room.name, selectedName));
  const selectedRoomId = resolvedRoomId(fixedRoomId, selectedId, selectedRoom.data, exactNamedRooms);
  const workflow = useQuery({
    queryKey: roomKeys.workflow(selectedRoomId ?? 0),
    queryFn: () => getRoomWorkflow(selectedRoomId!),
    enabled: lookingForStatus && selectedRoomId !== undefined && fixedStatuses === undefined,
  });
  const hashtags = useQuery({
    queryKey: [...SEARCH_ASSISTANT_KEY, "hashtags", selectedRoomId, debouncedFilterLookup],
    queryFn: () => listRoomHashtags(selectedRoomId!, {
      page: 1,
      pageSize: SUGGESTION_PAGE_SIZE,
      sort: "-threadCount",
      ...(debouncedFilterLookup === "" ? {} : { q: debouncedFilterLookup }),
    }),
    enabled: lookingForHashtag && selectedRoomId !== undefined && filterLookupSettled,
  });
  const statuses = fixedStatuses ?? workflow.data?.statuses;
  const resolvingRoom = roomOperand !== null && (selectedId !== null ? selectedRoom.isFetching : selectedRooms.isFetching);
  const showRoomHint = needsRoomHint(fixedRoomId, roomFilterActive, selectedRoomId, resolvingRoom);

  const suggestions = useMemo(() => makeSuggestions({
    token,
    rooms: rooms.data?.items ?? [],
    people: people.data?.items ?? [],
    statuses,
    hashtags: hashtags.data?.items ?? [],
    entityLookupReady: entityLookupEnabled && lookupSettled,
    selectedRoomId,
    filterLookup,
    filterLookupSettled,
    t,
  }), [entityLookupEnabled, filterLookup, filterLookupSettled, hashtags.data?.items, lookupSettled, people.data?.items, rooms.data?.items, selectedRoomId, statuses, t, token]);

  useImperativeHandle(ref, () => ({
    focusFirstSuggestion: () => {
      const first = suggestionRefs.current[0];
      first?.focus();
      return first !== undefined && first !== null;
    },
  }), []);

  function choose(replacement: string) {
    onInsert(token.start, token.end, replacement);
  }

  function insertAtCursor(value: string) {
    onInsert(caret, caret, value);
  }

  const helpItems = ["phrase", "exclude", "prefix", "word", "in", "from", "with", "owner", "status", "hashtag", "on", "before", "after", "during", "pin", "link", "reactionAny"] as const;
  const inserts: Record<(typeof helpItems)[number], string> = {
    phrase: '"exact phrase"',
    exclude: "-word",
    prefix: "prefix*",
    word: "word:disk",
    in: "in:",
    from: "from:",
    with: "with:",
    owner: "owner:",
    status: "status:",
    hashtag: "hashtag:",
    on: "on:",
    before: "before:",
    after: "after:",
    during: "during:",
    pin: "has:pin",
    link: "has:link",
    reactionAny: "has:reaction",
  };
  const examples: Partial<Record<(typeof helpItems)[number], string>> = {
    in: 'in:"Night Shift"',
    from: 'from:"Ada Lovelace"',
    with: 'with:"Ada Lovelace"',
    owner: 'owner:"Ada Lovelace"',
    status: "status:investigating",
    hashtag: "hashtag:incident",
    on: "on:YYYY-MM-DD",
    before: "before:YYYY-MM-DD",
    after: "after:YYYY-MM-DD",
    during: "during:YYYY-MM",
  };

  return (
    <>
      <Group gap="xs" className={classes.toolbar}>
        <Popover opened={helpOpened} onChange={setHelpOpened} position="bottom-start" shadow="md" width="min(42rem, calc(100vw - 2rem))">
          <Popover.Target>
            <Button
              type="button"
              variant="subtle"
              color="gray"
              size="compact-sm"
              leftSection={<IconAdjustmentsHorizontal size={16} />}
              aria-expanded={helpOpened}
              onClick={() => setHelpOpened((opened) => !opened)}
            >
              {t("search.syntax.open")}
            </Button>
          </Popover.Target>
          <Popover.Dropdown p="md" className={classes.helpDropdown}>
            <Stack gap="sm">
              <div>
                <Text fw={600}>{t("search.syntax.title")}</Text>
                <Text size="sm" c="dimmed">{t("search.syntax.description")}</Text>
              </div>
              <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
                {helpItems.map((item) => (
                  <UnstyledButton
                    type="button"
                    key={item}
                    className={classes.helpItem}
                    onClick={() => {
                      insertAtCursor(inserts[item]);
                      setHelpOpened(false);
                    }}
                  >
                    <Code>{examples[item] ?? inserts[item]}</Code>
                    <Text size="xs" c="dimmed">{t(`search.syntax.operators.${item}`)}</Text>
                  </UnstyledButton>
                ))}
              </SimpleGrid>
              <Divider />
              <Group justify="space-between" align="center" wrap="nowrap">
                <div>
                  <Text size="sm" fw={500}>{t("search.syntax.emoji.title")}</Text>
                  <Text size="xs" c="dimmed">{t("search.syntax.emoji.description")}</Text>
                </div>
                <Group gap={4} wrap="nowrap">
                  <EmojiButton label={t("search.syntax.emoji.reaction")} onSelect={(emoji) => insertAtCursor(`has:reaction:${emoji}`)} />
                  <EmojiButton label={t("search.syntax.emoji.contains")} onSelect={(emoji) => insertAtCursor(`has:emoji:${emoji}`)} />
                </Group>
              </Group>
            </Stack>
          </Popover.Dropdown>
        </Popover>
        <Text size="xs" c="dimmed" className={classes.tip}>
          <IconInfoCircle size={14} aria-hidden /> {t("search.syntax.tip")}
        </Text>
      </Group>

      {correction !== null && (
        <Paper withBorder p="xs" className={classes.correction} role="status">
          <Group justify="space-between" gap="xs">
            <Group gap="xs">
              <ThemeIcon variant="light" color="orange" size="sm"><IconBulb size={14} /></ThemeIcon>
              <Text size="sm">{t("search.syntax.correction", { value: correction.replacement })}</Text>
            </Group>
            <Button type="button" variant="light" color="gray" size="compact-sm" onClick={() => onInsert(correction.start, correction.end, correction.replacement)}>
              {t("search.syntax.applyCorrection")}
            </Button>
          </Group>
        </Paper>
      )}

      {showRoomHint && <Text size="xs" c="dimmed" role="note">{t("search.syntax.suggestions.chooseRoom")}</Text>}

      {suggestions.length > 0 && (
        <Paper withBorder shadow="xs" className={classes.suggestions} role="group" aria-label={t("search.syntax.suggestions.label")}>
          <Text size="xs" fw={600} c="dimmed" px="sm" pt="xs">{t("search.syntax.suggestions.label")}</Text>
          <Stack gap={0} py={4}>
            {suggestions.map((suggestion, index) => (
              <UnstyledButton
                type="button"
                key={suggestion.id}
                ref={(node) => { suggestionRefs.current[index] = node; }}
                className={classes.suggestion}
                onClick={() => choose(suggestion.insert)}
              >
                <div className={classes.suggestionText}>
                  <Text size="sm" fw={500}>{suggestion.label}</Text>
                  <Text size="xs" c="dimmed">{suggestion.description}</Text>
                </div>
                <ActionIcon component="span" variant="transparent" aria-hidden tabIndex={-1}><IconArrowRight size={16} /></ActionIcon>
              </UnstyledButton>
            ))}
          </Stack>
        </Paper>
      )}
    </>
  );
});

export default SearchQueryAssistant;
