import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, MultiSelect, Stack, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import type { RoomResponse } from "../api/rooms";
import type { TagCategory } from "../api/tagCategories";
import { ApiError } from "../api/http";
import { getThread, setThreadTags, type ThreadResponse } from "../api/threads";
import { useBeforeUnloadGuard } from "../hooks/useBeforeUnloadGuard";
import { useTagCategories } from "../hooks/useTagCategories";
import { PROBLEM_TYPES, isProblem } from "../utils/problems";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { roomKeys } from "../utils/roomKeys";
import { loadErrorMessage } from "../utils/saveError";
import { threadTagsBlock } from "../utils/threadAccess";
import { threadTagsErrorMessage } from "../utils/threadTagsErrors";
import { liveTagNames, MAX_THREAD_TAGS, retiredTagNames, sameTagSet, tagOptionGroups } from "../utils/threadTags";
import { showSuccessToast } from "../utils/toast";
import { accessibleRenderPill } from "./accessiblePill";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import RevisionConflictAlert from "./RevisionConflictAlert";
import TagPills from "./TagPills";

type Seed = { names: string[]; revision: number };

const seedOf = (thread: ThreadResponse, categories: readonly TagCategory[] | undefined): Seed => ({
  names: liveTagNames(thread.tags, categories),
  revision: thread.revision,
});

/**
 * The tag editor of the side panel (Hammurabi's `AdrTagsEditor`, ported): a searchable MultiSelect grouped by registry category, the WHOLE set
 * replaced at once under the thread's revision the editor was seeded at (a tag save and a details save share one token, 1.2 A3, so a stale
 * one is the orange reload notice, never a lost update). **Retired tags are dropped from the set it sends** (1.2 A6: a save that still
 * carries one is refused with a 400): the picker starts from the live tags only, and the retired ones are listed under it, struck through,
 * with the words "removed when you save" - and Save is open even when nothing else changed, because saving is how a retired tag leaves.
 * The draft follows the thread while nothing is picked and is kept, with the conflict alert, once something is; a closed thread (for a
 * non-steward) or an archived room disables the picker under a note, the draft kept.
 */
export default function ThreadTagsEditor({
  room,
  thread,
  steward,
  onClose,
}: {
  room: RoomResponse;
  thread: ThreadResponse;
  steward: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const categories = useTagCategories();
  const [seed, setSeed] = useState(() => seedOf(thread, categories.data));
  const [value, setValue] = useState<string[]>(seed.names);
  const [saving, setSaving] = useState(false);
  const [reloading, setReloading] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pickerRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    pickerRef.current?.focus();
  }, []);

  const picked = !sameTagSet(value, seed.names);
  useBeforeUnloadGuard(picked);
  // The thread moved on since the seed: follow it while nothing is picked, keep the draft (and say so) once something is.
  // The same goes for the registry arriving (or changing) under a clean draft: a tag it no longer lists is retired whatever the thread's cached flag says.
  const wanted = seedOf(thread, categories.data);
  const moved = thread.revision !== seed.revision;
  if ((moved || !sameTagSet(wanted.names, seed.names)) && !picked) {
    setSeed(wanted);
    setValue(wanted.names);
  }
  const showConflict = conflict || (!saving && picked && moved);
  const retired = retiredTagNames(thread.tags, categories.data);
  const block = threadTagsBlock(room, thread, steward);

  async function save() {
    setError(null);
    setConflict(false);
    setSaving(true);
    try {
      await setThreadTags(room.id, thread.number, value, seed.revision);
    } catch (err) {
      if (isProblem(err, PROBLEM_TYPES.revisionConflict)) setConflict(true);
      else setError(threadTagsErrorMessage(err, t));
      // A 400 is a name that is no live tag (any more): the registry and the thread this editor seeded from may be stale, so read both again.
      if (err instanceof ApiError && err.status === 400) await refreshQueriesAfterMutation(queryClient, ["tagCategories"], roomKeys.threads(room.id));
      // A thread closed (or a room archived) meanwhile, or a standing that moved: show the truth. A stale revision is told by the alert alone.
      if (isAccessRefusal(err) && !isProblem(err, PROBLEM_TYPES.revisionConflict)) await refreshQueriesAfterMutation(queryClient, roomKeys.all);
      setSaving(false);
      return;
    }
    showSuccessToast(t("threads.toast.tagsSaved"));
    // The thread (its new revision and tags), the list's rows and filter and the history; a tag change is not activity, so the rail stays.
    await refreshQueriesAfterMutation(queryClient, roomKeys.threads(room.id));
    setSaving(false);
    onClose();
  }

  async function reload() {
    setReloading(true);
    try {
      const fresh = await queryClient.fetchQuery({ queryKey: roomKeys.threadDetail(room.id, thread.number), queryFn: () => getThread(room.id, thread.number), staleTime: 0 });
      const next = seedOf(fresh, categories.data);
      setSeed(next);
      setValue(next.names);
      setConflict(false);
      setError(null);
    } catch (err) {
      setError(loadErrorMessage(err, t));
    }
    setReloading(false);
  }

  return (
    <Stack gap="xs">
      <MultiSelect
        ref={pickerRef}
        label={t("threads.tags.label")}
        description={t("threads.tags.hint")}
        placeholder={value.length === 0 ? t("threads.tags.placeholder") : undefined}
        data={tagOptionGroups(categories.data ?? [])}
        value={value}
        onChange={setValue}
        maxValues={MAX_THREAD_TAGS}
        renderPill={accessibleRenderPill((label) => t("common.filter.removeValue", { value: label }))}
        searchable
        clearable
        clearButtonProps={{ "aria-label": t("threads.tags.clear") }}
        nothingFoundMessage={t("threads.tags.empty")}
        disabled={categories.isLoading || block !== null}
      />
      {retired.length > 0 && (
        <Stack gap={4} data-retired="">
          <Text size="xs" c="dimmed">
            {t("threads.tags.retiredHint")}
          </Text>
          <TagPills tags={retired.map((name) => ({ name, retired: true }))} />
        </Stack>
      )}
      {block !== null && (
        <Text size="xs" c="dimmed">
          {t(block === "archived" ? "threads.tags.archivedNote" : "threads.tags.closedNote")}
        </Text>
      )}
      {categories.isError && <ErrorAlert error={categories.error} title={t("threads.tags.loadFailed")} />}
      {showConflict && (
        <RevisionConflictAlert
          onReload={() => void reload()}
          reloading={reloading}
          title={t("threads.tags.conflict.title")}
          message={t("threads.tags.conflict.message")}
          reloadLabel={t("threads.tags.conflict.reload")}
        />
      )}
      {error !== null && <FormErrorAlert>{error}</FormErrorAlert>}
      <Group gap="xs">
        <Button size="xs" onClick={() => void save()} loading={saving} disabled={categories.isLoading || block !== null || (!picked && retired.length === 0) || showConflict}>
          {t("threads.tags.save")}
        </Button>
        <Button size="xs" variant="default" onClick={onClose} disabled={saving}>
          {t("common.action.cancel")}
        </Button>
      </Group>
    </Stack>
  );
}
