import { useId, useLayoutEffect, useRef, useState, type ComponentProps, type KeyboardEvent, type SyntheticEvent } from "react";
import { useTranslation } from "react-i18next";
import { Combobox, Group, Text, VisuallyHidden, useCombobox } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { listRoomMembers } from "../api/rooms";
import { FILTER_DEBOUNCE_MS } from "../hooks/usePagedSort";
import { useKnownNames } from "../utils/knownNames";
import { editCanonical, mentionQuery, pickMention, toShown, toShownOffset, type MentionQuery, type Shown } from "../utils/mentionText";
import { foldDiacritics } from "../utils/text";
import { roomMemberPickerKey } from "../utils/roomMemberOptions";
import EmojiTextarea from "./EmojiTextarea";

/** Rows the list offers (the server filters by name; the rest are one more letter away). */
const LIST_SIZE = 8;

type MentionTextareaProps = Omit<ComponentProps<typeof EmojiTextarea>, "value" | "onChange" | "ref" | "measuredLength"> & {
  roomId: number;
  /** The CANONICAL text, with `<@id>` tokens; what `onChange` hands back is canonical too. */
  value?: string;
  onChange: (value: string) => void;
};

/**
 * `EmojiTextarea` that mentions people. The caller holds the canonical text (tokens and all: what is stored and sent); the box shows
 * `@Name` for every token whose name the session knows (`utils/mentionText.ts` maps both ways, and an edit that touches a mention removes it
 * whole). Typing `@` at the start of a word, then letters, lists the room's members (`GET /rooms/{id}/members?name=`, 300 ms debounced, eight
 * rows); ArrowDown/ArrowUp move, Enter or Tab picks, Escape closes (and Enter is a newline again); nothing opens during an IME composition.
 * While the list is open Enter picks, whatever else is held; closed, Enter keeps its normal meaning and so does the caller's `onKeyDown`.
 *
 * ARIA: the textarea keeps its `textbox` role (a `combobox` role is not allowed on a textarea) and names the list it controls
 * (`aria-controls`) and the highlighted option (`aria-activedescendant`); the list is a labelled `listbox`, and a visually hidden status line says
 * how many people match. The limit counts the stored text, not the shown one.
 */
export default function MentionTextarea({ roomId, value, onChange, maxLength, onKeyDown, onBlur, onSelect, ...rest }: MentionTextareaProps) {
  const { t } = useTranslation();
  const known = useKnownNames();
  const canonical = value ?? "";
  // Recomputed every render: the names the session knows can change without this component being told, and the box must show what they say now.
  const shown = toShown(canonical, known.get);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const listId = useId();
  const [query, setQuery] = useState<MentionQuery | null>(null);
  // The `@` (a shown offset) whose list Escape closed: it stays closed until that `@` is gone.
  const [dismissedAt, setDismissedAt] = useState<number | null>(null);
  const [active, setActive] = useState(0);
  const composing = useRef(false);
  // Where the caret goes after the next render (shown offset): a re-render with a different shown text moves it to the end.
  const pendingCaret = useRef<number | null>(null);

  const [term] = useDebouncedValue(query?.term ?? "", FILTER_DEBOUNCE_MS);
  const members = useQuery({
    // Under the room's members key (and so under ["rooms"]): a room refresh re-reads it.
    queryKey: [...roomMemberPickerKey(roomId), "mention", term],
    queryFn: () => listRoomMembers(roomId, { page: 1, pageSize: LIST_SIZE, sort: "name", name: term || undefined }),
    enabled: query !== null,
    placeholderData: keepPreviousData,
  });
  // Until the read for the CURRENT term is back (the debounce, the request) the list is the previous read: narrowed by the term in the meantime, so
  // a quick Enter never picks someone the typed letters no longer match.
  const settled = query !== null && members.data !== undefined && !members.isFetching && term === query.term;
  const typed = foldDiacritics(query?.term ?? "");
  const items = (members.data?.items ?? []).filter((member) => settled || foldDiacritics(member.name).includes(typed));
  const open = query !== null && dismissedAt !== query.from && items.length > 0;
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
    if (next === null) setDismissedAt(null);
    if (next?.from !== query?.from || next?.term !== query?.term) setActive(0);
    setQuery(next);
  }

  function handleChange(typed: string) {
    const el = textarea.current;
    // The caret is only meaningful when the box really holds the typed text (not for the picker's splice, which sets no DOM value).
    const caret = el !== null && el.value === typed ? el.selectionEnd : null;
    const result = editCanonical(shown, typed, caret, maxLength);
    if (result.canonical === canonical) return;
    const next = toShown(result.canonical, known.get);
    const shownCaret = toShownOffset(next, result.caret);
    pendingCaret.current = shownCaret;
    followCaret(next, shownCaret);
    onChange(result.canonical);
  }

  function pick(index: number) {
    const member = items[index];
    if (member === undefined || query === null) return;
    const result = pickMention(shown, query, query.from + 1 + query.term.length, member.userId, maxLength);
    if (result === null) return;
    known.remember([{ userId: member.userId, name: member.name }]);
    pendingCaret.current = toShownOffset(toShown(result.canonical, known.get), result.caret);
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
        setActive((index) => (index + (event.key === "ArrowDown" ? 1 : items.length - 1)) % items.length);
        return;
      }
      if (event.key === "Enter" || (event.key === "Tab" && !event.shiftKey)) {
        event.preventDefault();
        pick(active);
        return;
      }
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        setDismissedAt(query.from);
        return;
      }
    }
    onKeyDown?.(event);
  }

  function handleSelect(event: SyntheticEvent<HTMLTextAreaElement>) {
    onSelect?.(event);
    const el = event.currentTarget;
    // A selection event before the render of the last edit describes text this render no longer shows.
    if (el.value === shown.text) followCaret(shown, el.selectionStart === el.selectionEnd ? el.selectionEnd : null);
  }

  const status = !settled ? "" : items.length > 0 ? t("threads.mention.matches", { count: items.length }) : t("threads.mention.none");

  return (
    <>
      <Combobox store={store} keepMounted={false} position="bottom-start" onOptionSubmit={(id) => pick(items.findIndex((member) => String(member.userId) === id))}>
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
            aria-activedescendant={open ? `${listId}-${active}` : undefined}
          />
        </Combobox.Target>
        <Combobox.Dropdown>
          <Combobox.Options id={listId} aria-label={t("threads.mention.listLabel")}>
            {items.map((member, index) => (
              <Combobox.Option
                key={member.userId}
                id={`${listId}-${index}`}
                value={String(member.userId)}
                selected={index === active}
                aria-selected={index === active}
                onMouseEnter={() => setActive(index)}
              >
                <Group justify="space-between" gap="sm" wrap="nowrap">
                  <Text size="sm" truncate>
                    {member.name}
                  </Text>
                  <Text size="xs" c="dimmed" truncate>
                    {member.email}
                  </Text>
                </Group>
              </Combobox.Option>
            ))}
          </Combobox.Options>
        </Combobox.Dropdown>
      </Combobox>
      <VisuallyHidden role="status">{status}</VisuallyHidden>
    </>
  );
}
