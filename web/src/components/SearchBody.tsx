import { useEffect } from "react";
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
}: {
  label: string;
  baseKey: readonly unknown[];
  fetchPage: (query: SearchQuery) => Promise<ThreadSearchPage>;
  statuses?: RoomWorkflow["statuses"];
  showRoom: boolean;
  onGone?: () => void;
}) {
  const { t } = useTranslation();
  const { state, submit, setScope, setWatching, setPage, resetPage } = useSearchUrl();
  const { q, scope, watching, page } = state;
  // A term the URL holds but the server would refuse (hand-edited, too short) is never sent; the form says why. With the "Threads I watch"
  // toggle on, no term at all is a search too: the list of the threads the caller watches.
  const searchable = isSearchable(state);
  const { data, isLoading, isError, error, isPlaceholderData, refetch } = useQuery({
    queryKey: [...baseKey, q, scope, page, watching],
    queryFn: () =>
      fetchPage({ q: q === "" ? undefined : q, page, pageSize: SEARCH_PAGE_SIZE, closed: scopeToClosed(scope), watching: watching || undefined }),
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
  const emptyLabel = !watching ? t("search.empty", { q }) : q === "" ? t("search.watching.empty") : t("search.watching.emptyQ", { q });
  return (
    <Stack gap="md">
      {/* Submitting the term that is already searched reads it again (the way to retry a failed search, or to refresh a stale one); a new term is a new URL. */}
      <SearchForm
        label={label}
        q={q}
        scope={scope}
        watching={watching}
        onSubmit={(next) => (next === q ? void refetch() : submit(next))}
        onScope={setScope}
        onWatching={setWatching}
      />
      {searchable && isError && <FormErrorAlert>{searchErrorMessage(error, t)}</FormErrorAlert>}
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
