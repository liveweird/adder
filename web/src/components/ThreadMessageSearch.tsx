import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type RefObject } from "react";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, Button, Group, Loader, Paper, Stack, Text, TextInput, UnstyledButton } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconSearch, IconX } from "@tabler/icons-react";
import { searchThreadMessages } from "../api/search";
import type { RoomWorkflow } from "../api/rooms";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { messageJumpPath } from "../utils/roomLinks";
import { roomKeys } from "../utils/roomKeys";
import { detectStructuredSearch, queryProblem, SEARCH_PAGE_SIZE, searchErrorMessage, type QueryProblem, type SearchSyntax } from "../utils/searchParams";
import { insertSearchToken } from "../utils/searchQueryEditor";
import FormErrorAlert from "./FormErrorAlert";
import PaginationBar from "./PaginationBar";
import SearchQueryAssistant, { type SearchQueryAssistantHandle } from "./SearchQueryAssistant";
import Snippet from "./Snippet";
import classes from "./ThreadMessageSearch.module.css";

type Props = {
  roomId: number;
  threadNumber: number;
  open: boolean;
  triggerRef: RefObject<HTMLElement | null>;
  locationSearch: string;
  focusView: boolean;
  onClose: () => void;
  onSelect: (messageId: number) => void;
  statuses?: RoomWorkflow["statuses"];
};

type Submitted = { q: string; syntax?: SearchSyntax; zone?: string; page: number };

const plain = (segments: readonly { text: string }[]) => segments.map((segment) => segment.text).join("");

/** A bounded, submitted search of the open thread. It is intentionally outside the live room query tree. */
export default function ThreadMessageSearch({
  roomId,
  threadNumber,
  open,
  triggerRef,
  locationSearch,
  focusView,
  onClose,
  onSelect,
  statuses,
}: Props) {
  const { t } = useTranslation();
  const inputRef = useRef<HTMLInputElement>(null);
  const assistantRef = useRef<SearchQueryAssistantHandle>(null);
  const [draft, setDraft] = useState("");
  const [caret, setCaret] = useState(0);
  const [asked, setAsked] = useState(false);
  const [submitted, setSubmitted] = useState<Submitted | null>(null);
  const syntaxDraft = detectStructuredSearch(draft) ? "unified" as const : undefined;
  const problem: QueryProblem | null = asked ? queryProblem(draft, syntaxDraft !== undefined) : null;

  useEffect(() => {
    if (open) queueMicrotask(() => inputRef.current?.focus());
  }, [open]);

  const results = useQuery({
    queryKey: [...roomKeys.search(roomId), "thread-messages", threadNumber, submitted?.q, submitted?.syntax, submitted?.zone, submitted?.page],
    queryFn: ({ signal }) => searchThreadMessages(roomId, threadNumber, {
      q: submitted!.q,
      ...(submitted!.syntax !== undefined ? { syntax: submitted!.syntax, timeZone: submitted!.zone } : {}),
      page: submitted!.page,
      pageSize: SEARCH_PAGE_SIZE,
    }, signal),
    enabled: open && submitted !== null,
    staleTime: 30_000,
    placeholderData: keepPreviousData,
    refetchOnMount: false,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setAsked(true);
    if (queryProblem(draft, syntaxDraft !== undefined) !== null) return;
    const next: Submitted = {
      q: draft.trim(),
      ...(syntaxDraft === undefined ? {} : { syntax: syntaxDraft, zone: browserTimeZone() }),
      page: 1,
    };
    if (
      submitted?.page === 1
      && submitted.q === next.q
      && submitted.syntax === next.syntax
      && submitted.zone === next.zone
    ) {
      void results.refetch();
    } else {
      setSubmitted(next);
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

  function closeAndRestore() {
    onClose();
    requestAnimationFrame(() => {
      const target = triggerRef.current;
      if (target instanceof HTMLButtonElement) target.focus();
      else target?.querySelector<HTMLElement>("button")?.focus();
    });
  }

  if (!open) return null;
  const zone = browserTimeZone();
  // Never leave stale snippets on screen while a privacy-sensitive revalidation is in flight or after it failed.
  const items = results.isFetching || results.isError ? [] : results.data?.items ?? [];

  return (
    <Paper
      component="section"
      withBorder
      className={classes.panel}
      aria-label={t("threads.find.aria")}
      aria-busy={results.isFetching}
      onKeyDown={(event) => {
        if (event.key === "Escape") closeAndRestore();
      }}
    >
      <form role="search" onSubmit={submit} noValidate>
        <Stack gap="xs">
          <Group justify="space-between" align="center" wrap="nowrap">
            <Text fw={600} size="sm">{t("threads.find.title")}</Text>
            <UnstyledButton type="button" className={classes.close} aria-label={t("threads.find.close")} onClick={closeAndRestore}>
              <IconX size={17} aria-hidden />
            </UnstyledButton>
          </Group>
          <div className={classes.queryRow}>
            <TextInput
              ref={inputRef}
              type="search"
              name="q"
              label={t("threads.find.label")}
              value={draft}
              onChange={(event) => {
                setDraft(event.currentTarget.value);
                updateCaret(event.currentTarget);
              }}
              onClick={(event) => updateCaret(event.currentTarget)}
              onSelect={(event) => updateCaret(event.currentTarget)}
              onKeyDown={inputKeyDown}
              error={problem === null ? undefined : t(`search.error.${problem}`)}
              errorProps={{ role: "alert" }}
              leftSection={<IconSearch size={16} />}
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
            />
            <Button type="submit" className={classes.submit}>{t("threads.find.submit")}</Button>
          </div>
          <SearchQueryAssistant
            ref={assistantRef}
            query={draft}
            caret={caret}
            onInsert={insert}
            roomId={roomId}
            statuses={statuses}
          />
        </Stack>
      </form>

      {submitted !== null && (
        <div className={classes.results}>
          {results.isError && (
            <FormErrorAlert title={t("threads.find.failed")}>
              {searchErrorMessage(results.error, t, submitted.syntax !== undefined)}
            </FormErrorAlert>
          )}
          {results.isFetching && <Loader size="sm" aria-label={t("common.loading")} />}
          {!results.isFetching && !results.isError && results.data !== undefined && (
            <Text size="xs" fw={600} role="status" aria-live="polite" className={classes.count}>
              {t("threads.find.count", { count: results.data.total })}
            </Text>
          )}
          {!results.isFetching && !results.isError && results.data !== undefined && items.length === 0 && (
            <Text size="sm" c="dimmed" py="sm">{t("threads.find.empty", { q: submitted.q })}</Text>
          )}
          {items.length > 0 && (
            <Stack component="ol" gap={4} m={0} p={0} className={classes.list} aria-label={t("threads.find.results")}>
              {items.map((message) => (
                <li key={message.id}>
                  <Anchor
                    component={RouterLink}
                    to={messageJumpPath(roomId, threadNumber, message.id, locationSearch, focusView)}
                    className={classes.result}
                    aria-label={`${message.authorName}: ${plain(message.snippet)}`}
                    onClick={(event) => {
                      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                      onSelect(message.id);
                      onClose();
                    }}
                  >
                    <Group justify="space-between" gap="xs" wrap="nowrap">
                      <Text size="xs" fw={600}>{message.authorName}</Text>
                      <Text size="xs" c="dimmed">{formatDateTime(message.createdAt, zone)}</Text>
                    </Group>
                    <Text size="sm" className={classes.snippet}><Snippet segments={message.snippet} /></Text>
                  </Anchor>
                </li>
              ))}
            </Stack>
          )}
          {!results.isFetching && !results.isError && results.data !== undefined && results.data.total > SEARCH_PAGE_SIZE && (
            <PaginationBar
              total={results.data.total}
              page={submitted.page}
              pageSize={SEARCH_PAGE_SIZE}
              onPageChange={(page) => setSubmitted((current) => current === null ? null : { ...current, page })}
            />
          )}
        </div>
      )}
    </Paper>
  );
}
