import { useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Paper, SegmentedControl, Stack, Switch, Text, TextInput } from "@mantine/core";
import { IconSearch } from "@tabler/icons-react";
import type { RoomWorkflow } from "../api/rooms";
import { useTagCategories } from "../hooks/useTagCategories";
import { detectStructuredSearch, queryProblem, type QueryProblem, type SearchSyntax } from "../utils/searchParams";
import { insertSearchToken } from "../utils/searchQueryEditor";
import { tagOptionGroups } from "../utils/threadTags";
import { SCOPES, type Scope } from "../utils/threadListParams";
import FilterMultiSelect from "./FilterMultiSelect";
import SearchQueryAssistant, { type SearchQueryAssistantHandle } from "./SearchQueryAssistant";
import classes from "./SearchForm.module.css";

/**
 * The search box: a labelled `type="search"` input with the rules of matching under it, the Open / Closed / All control and a submit button. A
 * search is SUBMITTED, not run as you type (it is a heavier read under its own rate limit). The term is checked here first, with the server's
 * rules: a 400 never echoes the term, so this is the only place that can say what is wrong. When the URL's term changes (Back, a shared link,
 * the palette) the box takes it; a term the URL carries that cannot be searched shows its problem at once. The "Threads I watch" switch lists
 * the threads the caller watches: with it on, an EMPTY box is a search (the whole list) and a term narrows it. The "Tags" control (the registry,
 * grouped by category, the thread list's own picker) does the same for the curated tags (1.2 D3): with one picked, an empty box is a search
 * for the threads that carry any of them, and a term narrows those.
 */
export default function SearchForm({
  label,
  q,
  scope,
  watching,
  unread,
  threadTag,
  syntax,
  onSubmit,
  onScope,
  onWatching,
  onUnread,
  onThreadTag,
  assistantRoomId,
  assistantStatuses,
}: {
  label: string;
  q: string;
  scope: Scope;
  watching: boolean;
  unread: boolean;
  threadTag: readonly string[];
  syntax?: SearchSyntax;
  onSubmit: (q: string, syntax?: SearchSyntax) => void;
  onScope: (scope: Scope) => void;
  onWatching: (watching: boolean) => void;
  onUnread: (unread: boolean) => void;
  onThreadTag: (threadTag: string[]) => void;
  assistantRoomId?: number;
  assistantStatuses?: RoomWorkflow["statuses"];
}) {
  const { t } = useTranslation();
  // The registry is one cached read shared with the thread list's filter and the tag editor; a tag in the URL it no longer lists (retired, or not
  // read yet) keeps its pill under its own name, and a retired tag still finds the threads that kept it.
  const categories = useTagCategories();
  const listing = watching || unread || threadTag.length > 0;
  const [draft, setDraft] = useState(q);
  const [caret, setCaret] = useState(q.length);
  const inputRef = useRef<HTMLInputElement>(null);
  const assistantRef = useRef<SearchQueryAssistantHandle>(null);
  // Say what is wrong only once the reader asked (a submit) or the URL already holds a term; an empty box on arrival is not an error.
  const [asked, setAsked] = useState(q !== "");
  // Derived during render (the guarded idiom): the box follows the URL's term, and keeps the reader's focus while it does.
  const [seen, setSeen] = useState({ q, syntax });
  if (q !== seen.q || syntax !== seen.syntax) {
    setSeen({ q, syntax });
    setDraft(q);
    setCaret(q.length);
    setAsked(q !== "");
  }
  const draftValue = draft.trim();
  const syntaxDraft = draftValue === q ? syntax : detectStructuredSearch(draft) ? "unified" : undefined;
  // What is wrong with the box, with the watched list and the tag view in mind: an empty box is fine while either is on.
  const check = (raw: string): QueryProblem | null => (listing && raw.trim() === "" ? null : queryProblem(raw, syntaxDraft !== undefined));
  const problem = asked ? check(draft) : null;

  function submit(event: FormEvent) {
    event.preventDefault();
    setAsked(true);
    const value = draftValue;
    if (check(draft) === null) {
      const nextSyntax = value === q ? syntax : value !== "" && detectStructuredSearch(value) ? "unified" : undefined;
      onSubmit(value, nextSyntax);
    }
  }

  function updateCaret(element: HTMLInputElement) {
    setCaret(element.selectionStart ?? element.value.length);
  }

  function insert(start: number, end: number, replacement: string) {
    const next = insertSearchToken(draft, start, end, replacement);
    setDraft(next.query);
    setCaret(next.caret);
    queueMicrotask(() => {
      inputRef.current?.focus();
      inputRef.current?.setSelectionRange(next.caret, next.caret);
    });
  }

  function inputKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.nativeEvent.isComposing || event.key !== "ArrowDown") return;
    if (assistantRef.current?.focusFirstSuggestion()) event.preventDefault();
  }

  return (
    <Paper component="form" role="search" onSubmit={submit} noValidate withBorder className={classes.surface}>
      <Stack gap="md">
        <div className={classes.queryRow}>
          <TextInput
            ref={inputRef}
            className={classes.query}
            size="md"
            type="search"
            name="q"
            label={label}
            value={draft}
            onChange={(event) => {
              setDraft(event.currentTarget.value);
              updateCaret(event.currentTarget);
            }}
            onClick={(event) => updateCaret(event.currentTarget)}
            onSelect={(event) => updateCaret(event.currentTarget)}
            onKeyDown={inputKeyDown}
            description={t(watching ? "search.watching.hintOn" : threadTag.length > 0 ? "search.tags.hintOn" : unread ? "search.unread.hintOn" : "search.hint")}
            inputWrapperOrder={["label", "input", "error", "description"]}
            error={problem === null ? undefined : t(`search.error.${problem}`)}
            // Announced when it appears (a submit that was refused), not only found by a reader who goes back to the box.
            errorProps={{ role: "alert" }}
            leftSection={<IconSearch size={18} />}
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
          />
          <Button type="submit" size="md" className={classes.submit}>
            {t("search.submit")}
          </Button>
        </div>
        <SearchQueryAssistant
          ref={assistantRef}
          query={draft}
          caret={caret}
          onInsert={insert}
          roomId={assistantRoomId}
          statuses={assistantStatuses}
        />
        <fieldset className={classes.filters}>
          <legend className={classes.legend}>{t("search.filters.label")}</legend>
          <div className={classes.filterGrid}>
            <Stack gap={4} className={classes.scope}>
              <Text size="sm" fw={500}>{t("search.scope.label")}</Text>
              <SegmentedControl
                fullWidth
                aria-label={t("search.scope.aria")}
                value={scope}
                onChange={(value) => onScope(value as Scope)}
                data={SCOPES.map((value) => ({ value, label: t(`threads.scope.${value}`) }))}
              />
            </Stack>
            <FilterMultiSelect
              label={t("search.tags.label")}
              emptyLabel={t("threads.filters.all")}
              w="100%"
              data={tagOptionGroups(categories.data ?? [])}
              value={threadTag}
              onChange={onThreadTag}
              nothingFoundMessage={t("threads.filters.noTags")}
            />
            <Switch
              className={classes.watching}
              label={t("search.watching.label")}
              checked={watching}
              onChange={(event) => onWatching(event.currentTarget.checked)}
            />
            <Switch
              label={t("search.unread.label")}
              checked={unread}
              onChange={(event) => onUnread(event.currentTarget.checked)}
            />
          </div>
        </fieldset>
      </Stack>
    </Paper>
  );
}
