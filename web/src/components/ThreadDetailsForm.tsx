import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Stack, TextInput } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { useQueryClient } from "@tanstack/react-query";
import type { RoomResponse } from "../api/rooms";
import { getThread, updateThreadDetails, type ThreadResponse } from "../api/threads";
import { useBeforeUnloadGuard } from "../hooks/useBeforeUnloadGuard";
import { charCountDescription } from "../utils/charCount";
import { PROBLEM_TYPES, isProblem } from "../utils/problems";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { loadErrorMessage } from "../utils/saveError";
import { threadEditBlock } from "../utils/threadAccess";
import { detailsErrors, detailsFromThread, isDetailsDirty, threadDetailsErrorMessage, toDetailsBody, type ThreadDetailsValues } from "../utils/threadDetailsForm";
import { MAX_THREAD_TITLE_LENGTH, type WorkflowField } from "../utils/threadForm";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "./ConfirmActionModal";
import FormErrorAlert from "./FormErrorAlert";
import RevisionConflictAlert from "./RevisionConflictAlert";
import ThreadFieldInput from "./ThreadFieldInput";

/** What the draft was seeded from: the values, the thread's revision (what the save names in `X-Expected-Revision`), and the workflow's fields and revision at that moment. */
type Seed = { values: ThreadDetailsValues; revision: number; fields: readonly WorkflowField[]; workflowRevision: number };

function seedOf(thread: ThreadResponse, fields: readonly WorkflowField[], workflowRevision: number): Seed {
  return { values: detailsFromThread(thread, fields), revision: thread.revision, fields, workflowRevision };
}

/**
 * The details editor: the title and the room's active fields under `X-Expected-Revision`. Only a thread's OWN revision moves with a details
 * save (a status or owner change does not bump it), so the draft is seeded from the revision it read and re-seeded ONLY when the thread's
 * revision (or the room's fields) changed under a CLEAN form, never on every refetch: a status or owner change, a new message or a focus
 * refetch leaves a dirty draft alone. Someone else saving meanwhile under a DIRTY form shows the conflict alert at once (the save would be
 * refused), and a `409 revision-conflict` shows it too (a change to the room's fields under a dirty draft gets its own alert, same Reload); both keep the draft and offer Reload, the one way the newer version replaces it.
 * A closed thread and an archived room refuse the write (409), so the form is a disabled fieldset under a note, the draft kept (a save in flight only makes the inputs read-only, so the caret stays; a refused save puts the caret back on the first input with an error, or the title); a
 * `409 thread-closed` on save (closed while the form was open) says so and re-reads. A save sends only the fields that changed — a cleared
 * one as `null` — and ends the editor after the thread has been re-read; a reload warns while the draft differs, and Cancel asks first.
 */
export default function ThreadDetailsForm({
  room,
  thread,
  fields,
  workflowRevision,
  onDone,
}: {
  room: RoomResponse;
  thread: ThreadResponse;
  fields: readonly WorkflowField[];
  workflowRevision: number;
  /** Leaves the editor (after a save, or a Cancel the person confirmed). */
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [seed, setSeed] = useState(() => seedOf(thread, fields, workflowRevision));
  const [draft, setDraft] = useState(seed.values);
  const [showErrors, setShowErrors] = useState(false);
  const [saving, setSaving] = useState(false);
  const [reloading, setReloading] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, { open: askDiscard, close: closeDiscard }] = useDisclosure(false);
  const titleRef = useRef<HTMLInputElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  // Bumped when a save is refused by the form's own rules: the effect below puts the caret on the first input that shows an error.
  const [invalidTick, setInvalidTick] = useState(0);
  useEffect(() => {
    titleRef.current?.focus();
  }, []);
  useEffect(() => {
    if (invalidTick > 0) formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
  }, [invalidTick]);

  const dirty = isDetailsDirty(draft, seed.values);
  useBeforeUnloadGuard(dirty);
  // The thread (or the room's fields) moved on since the seed: follow it while nothing is typed; keep the draft, and say so, once something is.
  const moved = thread.revision !== seed.revision || workflowRevision !== seed.workflowRevision;
  if (moved && !dirty) {
    const next = seedOf(thread, fields, workflowRevision);
    setSeed(next);
    setDraft(next.values);
  }
  const block = threadEditBlock(room, thread);
  const errors = showErrors ? detailsErrors(draft, seed.values, seed.fields, t) : {};
  // Not while the save is in flight: its own refetch moves the revision before the editor ends.
  const threadMoved = !saving && dirty && thread.revision !== seed.revision;
  // The room's fields changed (a field added, retired or retyped) under a draft: its inputs are the old ones, so a save may be refused.
  const fieldsMoved = !saving && dirty && workflowRevision !== seed.workflowRevision;
  const showConflict = conflict || threadMoved;

  async function save() {
    setError(null);
    setConflict(false);
    if (Object.keys(detailsErrors(draft, seed.values, seed.fields, t)).length > 0) {
      setShowErrors(true);
      setInvalidTick((tick) => tick + 1);
      return;
    }
    setSaving(true);
    try {
      await updateThreadDetails(room.id, thread.number, toDetailsBody(draft, seed.values, seed.fields), seed.revision);
    } catch (err) {
      if (isProblem(err, PROBLEM_TYPES.revisionConflict)) setConflict(true);
      else setError(threadDetailsErrorMessage(err, t));
      // The click on Save took the focus; the caret goes back to the form (a thread closed meanwhile disables it, and there is nothing to focus).
      titleRef.current?.focus();
      // A thread closed (or a room archived) while the form was open, or a standing that moved: show the truth. A stale revision is told by the alert alone.
      if (isAccessRefusal(err) && !isProblem(err, PROBLEM_TYPES.revisionConflict)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
      setSaving(false);
      return;
    }
    showSuccessToast(t("threads.toast.detailsSaved"));
    // The thread (its new revision and values), the list, the history and the rail; the editor ends once they are read. A details save moves
    // no workflow count and changes no room header, so those stay as they are.
    await refreshQueriesAfterMutation(queryClient, ["rooms", "threads", room.id], ["rooms", "rail"]);
    setSaving(false);
    onDone();
  }

  async function reload() {
    setReloading(true);
    try {
      const fresh = await queryClient.fetchQuery({ queryKey: ["rooms", "threads", room.id, "detail", thread.number], queryFn: () => getThread(room.id, thread.number), staleTime: 0 });
      const next = seedOf(fresh, fields, workflowRevision);
      setSeed(next);
      setDraft(next.values);
      setConflict(false);
      setShowErrors(false);
      setError(null);
    } catch (err) {
      setError(loadErrorMessage(err, t));
    }
    setReloading(false);
  }

  return (
    <>
      <form
        ref={formRef}
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <Stack gap="sm">
          {block !== null && (
            <Alert color="gray" variant="light" role="note">
              {t(block === "archived" ? "threads.details.archivedNote" : "threads.details.closedNote")}
            </Alert>
          )}
          {/* A disabled fieldset turns every input off at once: a closed thread or an archived room. A save in flight only makes them read-only, so the caret stays. */}
          <fieldset disabled={block !== null} style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
            <Stack gap="sm">
              <TextInput
                ref={titleRef}
                label={t("threads.new.titleLabel")}
                required
                maxLength={MAX_THREAD_TITLE_LENGTH}
                description={charCountDescription(draft.title.length, MAX_THREAD_TITLE_LENGTH)}
                inputWrapperOrder={["label", "input", "description", "error"]}
                value={draft.title}
                readOnly={saving}
                onChange={(event) => setDraft({ ...draft, title: event.currentTarget.value })}
                error={errors.title}
              />
              {seed.fields.map((field) => (
                <ThreadFieldInput
                  key={field.key}
                  roomId={room.id}
                  field={field}
                  draft={draft.fields[field.key]}
                  onChange={(next) => setDraft({ ...draft, fields: { ...draft.fields, [field.key]: next } })}
                  error={errors[`fields.${field.key}`]}
                  readOnly={saving}
                />
              ))}
            </Stack>
          </fieldset>
          {fieldsMoved && !showConflict && (
            <RevisionConflictAlert
              onReload={() => void reload()}
              reloading={reloading}
              title={t("threads.details.fieldsMoved.title")}
              message={t("threads.details.fieldsMoved.message")}
              reloadLabel={t("threads.details.conflict.reload")}
            />
          )}
          {showConflict && (
            <RevisionConflictAlert
              onReload={() => void reload()}
              reloading={reloading}
              title={t("threads.details.conflict.title")}
              message={t("threads.details.conflict.message")}
              reloadLabel={t("threads.details.conflict.reload")}
            />
          )}
          {error && <FormErrorAlert>{error}</FormErrorAlert>}
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={() => (dirty ? askDiscard() : onDone())} disabled={saving}>
              {t("common.action.cancel")}
            </Button>
            <Button type="submit" loading={saving} disabled={!dirty || block !== null}>
              {t("common.action.save")}
            </Button>
          </Group>
        </Stack>
      </form>
      <ConfirmActionModal
        opened={confirming}
        onClose={closeDiscard}
        title={t("common.discard.title")}
        message={t("common.discard.message")}
        cancelLabel={t("common.action.keepEditing")}
        confirmLabel={t("common.action.discard")}
        onConfirm={() => {
          closeDiscard();
          onDone();
        }}
      />
    </>
  );
}
