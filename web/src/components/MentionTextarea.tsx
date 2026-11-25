import { useId, useLayoutEffect, useRef, useState, type ComponentProps, type KeyboardEvent, type SyntheticEvent } from "react";
import { useTranslation } from "react-i18next";
import { Combobox, Group, Text, VisuallyHidden, useCombobox } from "@mantine/core";
import { IconUserPlus } from "@tabler/icons-react";
import { useQuery } from "@tanstack/react-query";
import { getRoom } from "../api/rooms";
import { useMentionCandidates, type CandidateGroup } from "../hooks/useMentionCandidates";
import { useKnownNames } from "../utils/knownNames";
import { mentionsEveryone } from "../utils/mentions";
import { editCanonical, mentionQuery, mentionReplacementRequest, pickToken, toCanonicalOffset, toShown, toShownOffset, type MentionQuery, type MentionReplacementRequest, type Selection, type Shown } from "../utils/mentionText";
import { roomKeys } from "../utils/roomKeys";
import EmojiTextarea from "./EmojiTextarea";
import { DIRECTORY_MIN_TERM } from "./UserDirectorySelect";

type MentionTextareaProps = Omit<ComponentProps<typeof EmojiTextarea>, "value" | "onChange" | "ref" | "measuredLength"> & {
  roomId: number;
  /** The CANONICAL text, with tokens (`<@id>`, `<@team:id>`, `<@watchers>`, `<@everyone>`); what `onChange` hands back is canonical too. */
  value?: string;
  onChange: (value: string) => void;
  /** Whether the list offers `@watchers` (default yes): a thread that is not started yet has no watchers but its author. */
  offerWatchers?: boolean;
  /** Offered as a listbox action after a directory-sized term has no room-member match. */
  onAddAndMention?: (request: MentionReplacementRequest) => void;
};

const GROUP_LABELS = {
  people: "threads.mention.groupPeople",
  teams: "threads.mention.groupTeams",
  groups: "threads.mention.groupGroups",
} as const satisfies Record<CandidateGroup, string>;

/**
 * `EmojiTextarea` that mentions people, teams and groups. The caller holds the canonical text (tokens and all: what is stored and sent); the box
 * shows `@Name` for every person whose name the session knows, `@handle` for a team whose handle it knows and `@everyone` / `@watchers`
 * (`utils/mentionText.ts` maps both ways, and an edit that touches a mention removes it whole). Typing `@` at the start of a word, then letters,
 * lists the room's members (`GET /rooms/{id}/members?name=`, 300 ms debounced, eight rows), then the teams whose handle matches (`GET
 * /teams?handle=`, from the first letter) and the groups (`@everyone`, `@watchers`; `hooks/useMentionCandidates.ts`); ArrowDown/ArrowUp move,
 * Enter or Tab picks, Escape closes (and Enter is a newline again); nothing opens during an IME composition. A draft that holds `@everyone`
 * shows a non-blocking status line with the room's member count (no confirm).
 * While the list is open Enter picks, whatever else is held; closed, Enter keeps its normal meaning and so does the caller's `onKeyDown`.
 *
 * ARIA: the textarea keeps its `textbox` role (a `combobox` role is not allowed on a textarea) and names the list it controls
 * (`aria-controls`) and the highlighted option (`aria-activedescendant`); the list is a labelled `listbox` of labelled `group`s (People, Teams,
 * Groups), and a visually hidden status line says how many rows match. The limit counts the stored text, not the shown one.
 */
export default function MentionTextarea({ roomId, value, onChange, maxLength, onKeyDown, onBlur, onSelect, offerWatchers = true, onAddAndMention, ...rest }: MentionTextareaProps) {
  const { t } = useTranslation();
  const known = useKnownNames();
  const canonical = value ?? "";
  // Recomputed every render: the names the session knows can change without this component being told, and the box must show what they say now.
  const shown = toShown(canonical, known.get, known.getTeam);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const listId = useId();
  const [query, setQuery] = useState<MentionQuery | null>(null);
  // The `@` (a CANONICAL offset, so typing earlier in the text moves it along, `handleChange`) whose list Escape closed, or that a pick that did
  // not fit left open: it stays closed until that `@` is gone.
  const [dismissedAt, setDismissedAt] = useState<number | null>(null);
  const [active, setActive] = useState(0);
  const composing = useRef(false);
  // The box's selection as the last `select` event reported it: what an edit over a selection replaced (null once an edit used it).
  const selection = useRef<Selection | null>(null);
  // Where the caret goes after the next render (shown offset): a re-render with a different shown text moves it to the end.
  const pendingCaret = useRef<number | null>(null);

  const { candidates, settled } = useMentionCandidates(roomId, query, offerWatchers);
  const items = candidates;
  const directoryTerm = query?.term.trim() ?? "";
  const offerAdd = onAddAndMention !== undefined && query !== null && settled && [...directoryTerm].length >= DIRECTORY_MIN_TERM &&
    !items.some((item) => item.group === "people");
  const optionCount = items.length + (offerAdd ? 1 : 0);
  const open = query !== null && dismissedAt !== toCanonicalOffset(shown, query.from) && optionCount > 0;
  // A refetch can shrink the list under the highlighted row.
  const current = Math.min(active, optionCount - 1);
  const store = useCombobox({ opened: open });

  useLayoutEffect(() => {
    const caret = pendingCaret.current;
    if (caret === null) return;
    pendingCaret.current = null;
    const el = textarea.current;
    if (el !== null && el.value === shown.text) el.setSelectionRange(caret, caret);
  });

  function followCaret(from: Shown, caret: number | null) {
    const next = caret === null || composing.current ? null : mentionQuery(from, caret);
    if (next?.from !== query?.from || next?.term !== query?.term) setActive(0);
    setQuery(next);
  }

  function handleChange(typed: string) {
    const el = textarea.current;
    // The caret is only meaningful when the box really holds the typed text (not for the picker's splice, which sets no DOM value).
    const caret = el !== null && el.value === typed ? el.selectionEnd : null;
    const result = editCanonical(shown, typed, caret, maxLength, selection.current);
    selection.current = null;
    if (result.canonical === canonical) {
      // Nothing to store (the cap cut the insert to nothing): React puts the old value back and the caret would jump to the end.
      const restored = toShownOffset(shown, result.caret);
      queueMicrotask(() => {
        if (el !== null && el.value === shown.text) el.setSelectionRange(restored, restored);
      });
      return;
    }
    // The dismissed `@` moves with the edit and is gone when the edit replaced it.
    setDismissedAt((at) => {
      if (at === null || (at >= result.from && at < result.to)) return null;
      const moved = at >= result.to ? at + (result.caret - result.to) : at;
      return result.canonical[moved] === "@" ? moved : null;
    });
    const next = toShown(result.canonical, known.get, known.getTeam);
    const shownCaret = toShownOffset(next, result.caret);
    pendingCaret.current = shownCaret;
    followCaret(next, shownCaret);
    onChange(result.canonical);
  }

  function pick(index: number) {
    if (offerAdd && index === items.length && query !== null) {
      onAddAndMention?.(mentionReplacementRequest(shown, query));
      selection.current = null;
      setQuery(null);
      setDismissedAt(null);
      return;
    }
    const item = items[index];
    if (item === undefined || query === null) return;
    const result = pickToken(shown, query, query.from + 1 + query.term.length, item.token, maxLength);
    if (result === null) {
      // The token does not fit in the limit: close the list instead of leaving a pick that does nothing.
      setDismissedAt(toCanonicalOffset(shown, query.from));
      return;
    }
    item.remember(known);
    pendingCaret.current = toShownOffset(toShown(result.canonical, known.get, known.getTeam), result.caret);
    selection.current = null;
    setQuery(null);
    setDismissedAt(null);
    onChange(result.canonical);
    textarea.current?.focus();
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // 229 is the keyCode a browser gives the key that ends an IME composition: it is the composition's, not a pick.
    if (open && query !== null && !event.nativeEvent.isComposing && event.keyCode !== 229) {
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        setActive((index) => (Math.min(index, optionCount - 1) + (event.key === "ArrowDown" ? 1 : optionCount - 1)) % optionCount);
        return;
      }
      if (event.key === "Enter" || (event.key === "Tab" && !event.shiftKey)) {
        event.preventDefault();
        pick(current);
        return;
      }
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        setDismissedAt(toCanonicalOffset(shown, query.from));
        return;
      }
    }
    onKeyDown?.(event);
  }

  function handleSelect(event: SyntheticEvent<HTMLTextAreaElement>) {
    onSelect?.(event);
    const el = event.currentTarget;
    selection.current = el.selectionStart < el.selectionEnd ? { start: el.selectionStart, end: el.selectionEnd } : null;
    // A selection event before the render of the last edit describes text this render no longer shows.
    if (el.value === shown.text) followCaret(shown, el.selectionStart === el.selectionEnd ? el.selectionEnd : null);
  }

  const status = !settled ? "" : optionCount > 0 ? t("threads.mention.matches", { count: optionCount }) : t("threads.mention.none");
  // The room's size, read only while the draft names everyone (the room page's own read: the same cache entry).
  const everyone = mentionsEveryone(canonical);
  const room = useQuery({ queryKey: roomKeys.detail(roomId), queryFn: () => getRoom(roomId), enabled: everyone });
  const groups = (["people", "teams", "groups"] as const).filter((group) => items.some((item) => item.group === group));

  return (
    <>
      <Combobox store={store} keepMounted={false} position="bottom-start"
        onOptionSubmit={(id) => pick(id === "add-and-mention" ? items.length : items.findIndex((item) => item.key === id))}>
        <Combobox.Target withKeyboardNavigation={false} withAriaAttributes={false}>
          <EmojiTextarea
            {...rest}
            ref={textarea}
            value={shown.text}
            measuredLength={canonical.length}
            maxLength={maxLength}
            onChange={handleChange}
            onKeyDown={handleKeyDown}
            onSelect={handleSelect}
            onBlur={(event) => {
              setQuery(null);
              setDismissedAt(null);
              onBlur?.(event);
            }}
            onCompositionStart={() => {
              composing.current = true;
              setQuery(null);
            }}
            onCompositionEnd={(event) => {
              composing.current = false;
              const el = event.currentTarget;
              if (el.value === shown.text) followCaret(shown, el.selectionStart === el.selectionEnd ? el.selectionEnd : null);
            }}
            autoComplete="off"
            aria-autocomplete="list"
            aria-haspopup="listbox"
            aria-controls={open ? listId : undefined}
            aria-activedescendant={open ? `${listId}-${current}` : undefined}
          />
        </Combobox.Target>
        <Combobox.Dropdown>
          <Combobox.Options id={listId} aria-label={t("threads.mention.listLabel")}>
            {groups.map((group) => (
              <Combobox.Group key={group} label={t(GROUP_LABELS[group])}>
                {items.map((item, index) =>
                  item.group !== group ? null : (
                    <Combobox.Option
                      key={item.key}
                      id={`${listId}-${index}`}
                      value={item.key}
                      selected={index === current}
                      aria-selected={index === current}
                      onMouseEnter={() => setActive(index)}
                    >
                      <Group justify="space-between" gap="sm" wrap="nowrap">
                        <Text size="sm" truncate>
                          {item.name}
                        </Text>
                        {/* The highlighted option is filled with the brand colour (white ink): dimmed grey would not clear AA on it. */}
                        <Text size="xs" c={index === current ? "inherit" : "dimmed"} truncate>
                          {item.detailKey !== undefined ? t(item.detailKey) : item.detail}
                        </Text>
                      </Group>
                    </Combobox.Option>
                  ),
                )}
              </Combobox.Group>
            ))}
            {offerAdd && (
              <Combobox.Option
                id={`${listId}-${items.length}`}
                value="add-and-mention"
                selected={current === items.length}
                aria-selected={current === items.length}
                onMouseDown={(event) => event.preventDefault()}
                onMouseEnter={() => setActive(items.length)}
              >
                <Group justify="space-between" gap="sm" wrap="nowrap">
                  <Group gap="xs" wrap="nowrap">
                    <IconUserPlus size={16} aria-hidden="true" />
                    <Text size="sm" fw={600} truncate>{t("threads.composer.addAndMentionTerm", { term: directoryTerm })}</Text>
                  </Group>
                  <Text size="xs" c={current === items.length ? "inherit" : "dimmed"} truncate>
                    {t("threads.composer.addAndMentionTermHint")}
                  </Text>
                </Group>
              </Combobox.Option>
            )}
          </Combobox.Options>
        </Combobox.Dropdown>
      </Combobox>
      <VisuallyHidden role="status">{status}</VisuallyHidden>
      {everyone && room.data !== undefined && (
        <Text size="xs" c="dimmed" role="status" data-testid="everyone-note">
          {t("threads.mention.everyoneNote", { count: Math.max(0, room.data.memberCount - 1) })}
        </Text>
      )}
    </>
  );
}
