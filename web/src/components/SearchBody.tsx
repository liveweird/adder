import { useEffect } from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconSearch } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import type { RoomWorkflow } from "../api/rooms";
import type { SearchQuery, ThreadSearchPage } from "../api/search";
import { useSearchUrl } from "../hooks/useSearchUrl";
import { isSearchable, scopeToClosed, SEARCH_PAGE_SIZE, searchErrorMessage } from "../utils/searchParams";
import EmptyState from "./EmptyState";
import FormErrorAlert from "./FormErrorAlert";
import LoadingBlock from "./LoadingBlock";
import PaginationBar from "./PaginationBar";
import SearchForm from "./SearchForm";
import SearchResults from "./SearchResults";

/** Search results are not live (a busy room would spend the reader's rate limit on refetches): a read stays fresh this long. */
const SEARCH_STALE_MS = 30_000;

/** What an empty answer says: the term alone, the watched list, the tag view, or the pair of them (each says which filter found nothing). */
function emptyMessage(t: TFunction, { q, watching, unread, tagged }: { q: string; watching: boolean; unread: boolean; tagged: boolean }): string {
  if (unread) return t("search.unread.empty");
  if (!watching && !tagged) return t("search.empty", { q });
  const base = watching ? (tagged ? "search.watchingTagged" : "search.watching") : "search.tags";
  return q === "" ? t(`${base}.empty`) : t(`${base}.emptyQ`, { q });
}

/**
 * The body both search pages share: the form, then the results of the term the URL holds. `baseKey` is the query-key prefix of the page's
 * reads (the room's own, or the search across rooms), `fetchPage` its request. `statuses` is the room's workflow where there is one;
 * `showRoom` is the search across rooms. `onGone` runs when the room answers 404 (the caller lost it, or it was deleted) so the page can re-read it.
 */
export default function SearchBody({
  label,
  baseKey,
  fetchPage,
  statuses,
  showRoom,
  onGone,
  initialLabel,
  roomId,
}: {
  label: string;
  baseKey: readonly unknown[];
  fetchPage: (query: SearchQuery) => Promise<ThreadSearchPage>;
  statuses?: RoomWorkflow["statuses"];
  showRoom: boolean;
  onGone?: () => void;
  initialLabel?: string;
  roomId?: number;
}) {
  const { t } = useTranslation();
  const { state, submit, setScope, setWatching, setUnread, setThreadTag, setPage, resetPage } = useSearchUrl();
  const { q, syntax, timeZone, scope, watching, unread, threadTag, page } = state;
  // A term the URL holds but the server would refuse (hand-edited, too short) is never sent; the form says why. With the "Threads I watch"
  // toggle on or a tag picked, no term at all is a search too: the threads the caller watches, or the ones that carry a chosen tag.
  const searchable = isSearchable(state);
  const { data, isLoading, isError, error, isPlaceholderData, refetch } = useQuery({
    queryKey: [...baseKey, q, scope, page, watching, unread, threadTag, syntax, timeZone],
    queryFn: () =>
      fetchPage({
        q: q === "" ? undefined : q,
        page,
        pageSize: SEARCH_PAGE_SIZE,
        closed: scopeToClosed(scope),
        watching: watching || undefined,
        unread: unread || undefined,
        threadTag: threadTag.length > 0 ? threadTag : undefined,
        ...(syntax !== undefined ? { syntax, timeZone } : {}),
      }),
    enabled: searchable,
    staleTime: SEARCH_STALE_MS,
    placeholderData: keepPreviousData,
  });
  const gone = error instanceof ApiError && error.status === 404;
  useEffect(() => {
    if (gone) onGone?.();
  }, [gone, onGone]);
  // The page was emptied under the reader: step back to the first.
  const emptied = searchable && !!data && !isPlaceholderData && data.items.length === 0 && data.total > 0 && page > 1;
  useEffect(() => {
    if (emptied) resetPage();
  }, [emptied, resetPage]);

  const answered = searchable && data !== undefined;
  const emptyLabel = emptyMessage(t, { q, watching, unread, tagged: threadTag.length > 0 });
  return (
    <Stack gap="md">
      {/* Submitting the term that is already searched reads it again (the way to retry a failed search, or to refresh a stale one); a new term is a new URL. */}
      <SearchForm
        label={label}
        q={q}
        scope={scope}
        watching={watching}
        unread={unread}
        threadTag={threadTag}
        syntax={syntax}
        onSubmit={(next, nextSyntax) => (next === q && nextSyntax === syntax ? void refetch() : submit(next, nextSyntax))}
        onScope={setScope}
        onWatching={setWatching}
        onUnread={setUnread}
        onThreadTag={setThreadTag}
        assistantRoomId={roomId}
        assistantStatuses={statuses}
      />
      {!searchable && initialLabel && <EmptyState icon={IconSearch} label={initialLabel} />}
      {searchable && isError && <FormErrorAlert>{searchErrorMessage(error, t, syntax !== undefined)}</FormErrorAlert>}
      {searchable && isLoading && <LoadingBlock />}
      {/* A live region that is always there while a search is shown, so the count is announced when it changes. */}
      {searchable && (
        <Text size="sm" c="dimmed" role="status">
          {answered && !isPlaceholderData ? t("search.results.count", { count: data.total }) : ""}
        </Text>
      )}
      {answered && data.items.length === 0 && !isPlaceholderData && !isError && <EmptyState icon={IconSearch} label={emptyLabel} />}
      {answered && data.items.length > 0 && (
        <div aria-busy={isPlaceholderData} style={{ opacity: isPlaceholderData ? 0.5 : undefined }}>
          <Stack gap="md">
            <SearchResults hits={data.items} statuses={statuses} showRoom={showRoom} label={t("search.results.aria")} />
            {data.total > SEARCH_PAGE_SIZE && <PaginationBar total={data.total} page={page} pageSize={SEARCH_PAGE_SIZE} onPageChange={setPage} />}
          </Stack>
        </div>
      )}
    </Stack>
  );
}
