import { forwardRef, useImperativeHandle, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Button, Code, Divider, Group, Paper, Popover, SimpleGrid, Stack, Switch, Text, ThemeIcon, UnstyledButton } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { IconAdjustmentsHorizontal, IconArrowRight, IconBulb, IconInfoCircle } from "@tabler/icons-react";
import { listRooms } from "../api/rooms";
import { searchUserDirectory } from "../api/users";
import {
  contextualSearchToken,
  operatorCorrection,
  SEARCH_ASSISTANT_KEY,
  searchLookupTerm,
  searchOperatorSuggestions,
} from "../utils/searchQueryEditor";
import EmojiButton from "./EmojiButton";
import classes from "./SearchQueryAssistant.module.css";

const SUGGESTION_PAGE_SIZE = 8;
const LOOKUP_DEBOUNCE_MS = 300;
export type SearchQueryAssistantHandle = { focusFirstSuggestion: () => boolean };

type Suggestion = { id: string; insert: string; label: string; description: string };

type Props = {
  query: string;
  caret: number;
  structured: boolean;
  onModeChange: (structured: boolean) => void;
  onInsert: (start: number, end: number, replacement: string) => void;
};

const SearchQueryAssistant = forwardRef<SearchQueryAssistantHandle, Props>(function SearchQueryAssistant(
  { query, caret, structured, onModeChange, onInsert },
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
  const lookingForPerson = token.operator === "from" || token.operator === "with" || token.operator === "owner";
  const lookupEnabled = (lookingForRoom || lookingForPerson) && lookup !== null;

  const rooms = useQuery({
    queryKey: [...SEARCH_ASSISTANT_KEY, "rooms", debouncedLookup],
    queryFn: () => listRooms({ page: 1, pageSize: SUGGESTION_PAGE_SIZE, sort: "name", name: debouncedLookup, member: true }),
    enabled: lookupEnabled && lookupSettled && lookingForRoom,
  });
  const people = useQuery({
    queryKey: [...SEARCH_ASSISTANT_KEY, "people", debouncedLookup],
    queryFn: () => searchUserDirectory({ q: debouncedLookup, page: 1, pageSize: SUGGESTION_PAGE_SIZE, sort: "name" }),
    enabled: lookupEnabled && lookupSettled && lookingForPerson,
  });

  const suggestions = useMemo<Suggestion[]>(() => {
    if (lookingForRoom && lookupEnabled && lookupSettled) {
      return (rooms.data?.items ?? []).map((room) => ({
        id: `room-${room.id}`,
        insert: `in:#${room.id}`,
        label: room.name,
        description: t("search.syntax.suggestions.room"),
      }));
    }
    if (lookingForPerson && lookupEnabled && lookupSettled) {
      return (people.data?.items ?? []).map((person) => ({
        id: `person-${person.id}`,
        insert: `${token.operator}:#${person.id}`,
        label: person.name,
        description: person.email,
      }));
    }
    if (token.text === "" || token.value !== "") return [];
    return searchOperatorSuggestions(token.text).map((item) => ({
      ...item,
      label: item.insert,
      description: t(`search.syntax.operators.${item.id}`),
    }));
  }, [lookingForPerson, lookingForRoom, lookupEnabled, lookupSettled, people.data?.items, rooms.data?.items, t, token]);

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

  const helpItems = ["phrase", "exclude", "prefix", "in", "from", "with", "owner", "on", "before", "after", "during", "pin", "link", "reactionAny"] as const;
  const inserts: Record<(typeof helpItems)[number], string> = {
    phrase: '"exact phrase"',
    exclude: "-word",
    prefix: "prefix*",
    in: "in:",
    from: "from:",
    with: "with:",
    owner: "owner:",
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
        <Switch
          size="xs"
          label={t("search.syntax.mode")}
          checked={structured}
          onChange={(event) => onModeChange(event.currentTarget.checked)}
        />
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
