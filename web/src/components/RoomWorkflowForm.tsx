import { useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Paper, Stack, Text } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import type { RoomResponse } from "../api/rooms";
import { updateRoomWorkflow } from "../api/rooms";
import { useDiscardGuard } from "../hooks/useDiscardGuard";
import { PROBLEM_TYPES, isProblem } from "../utils/problems";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { roomPath } from "../utils/roomLinks";
import { roomWorkflowSaveErrorMessage } from "../utils/roomWorkflowForm";
import { loadErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import { displayName, hasWorkflowErrors, isWorkflowDirty, toRoomWorkflowBody, validateWorkflowDraft, type WorkflowDraft } from "../utils/workflowForm";
import ConfirmActionModal from "./ConfirmActionModal";
import FormErrorAlert from "./FormErrorAlert";
import RegistryEditorActions from "./RegistryEditorActions";
import RevisionConflictAlert from "./RevisionConflictAlert";
import RoomWorkflowView from "./RoomWorkflowView";
import WorkflowEditor from "./WorkflowEditor";

/**
 * The room's workflow: the editor over a draft, its notes and its save. The draft is seeded ONCE from `seed` (read at `revision`,
 * which the save sends as `X-Expected-Revision`): a background refetch never rebases it, so the page re-seeds by changing this
 * component's `key`, which `onReseed` (refetch, then seed) does after a save and on the conflict alert's Reload.
 *
 * Only an `editable` caller (the room's OWNER or an ADMIN, on a room that is not archived; UX only, the server guards) edits; for
 * anyone else the workflow is plain text (`RoomWorkflowView`) under a note that says why. The page owns the save `error`, so a
 * refusal that turns the editor read-only (which re-seeds this form) stays on screen. A `409 revision-conflict` keeps the draft and offers
 * Reload; a `409 status-in-use` re-opens the retire dialog for the statuses it names; a 403, 404 or 409 re-reads the room so the
 * page follows the caller's real standing. When the save committed but the re-seed read failed, the editor FREEZES behind a
 * reload prompt (Lettuce's `staleAfterSave`): its rows lack the new revision, so another save would only conflict. The editor is
 * locked while a save is in flight. A re-seed (which re-keys this form) leaves focus nowhere, so `focusOnMount` moves it to the
 * workflow group.
 */
export default function RoomWorkflowForm({
  room,
  seed,
  revision,
  editable,
  focusOnMount,
  error,
  onError,
  onReseed,
}: {
  room: RoomResponse;
  seed: WorkflowDraft;
  revision: number;
  editable: boolean;
  focusOnMount: boolean;
  error: string | null;
  onError: (message: string | null) => void;
  onReseed: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const hintId = useId();
  const [draft, setDraft] = useState(seed);
  const [showErrors, setShowErrors] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [reloading, setReloading] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [conflictKeys, setConflictKeys] = useState<string[] | undefined>(undefined);
  const [staleAfterSave, setStaleAfterSave] = useState(false);
  const groupRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (focusOnMount) groupRef.current?.focus();
  }, [focusOnMount]);

  const errors = validateWorkflowDraft(draft);
  // Shown after a blocked save, for as long as the draft stays invalid.
  const invalid = showErrors && hasWorkflowErrors(errors);
  const dirty = !staleAfterSave && isWorkflowDirty(draft, seed);
  const guard = useDiscardGuard({ isDirty: dirty, to: roomPath(room.id) });

  /**
   * A `status-in-use` for a status that is still in the document means its CATEGORY was changed (a removed one re-opens the
   * retire dialog instead): threads cannot be moved out of it, so the only way on is to set the category back.
   */
  function failureMessage(err: unknown): string {
    if (isProblem(err, PROBLEM_TYPES.statusInUse)) {
      const kept = draft.statuses.filter((status) => err.keys.includes(status.key.trim())).map(displayName);
      if (kept.length > 0) return t("rooms.workflow.saveStatusRecategorised", { statuses: kept.join(", ") });
    }
    return roomWorkflowSaveErrorMessage(err, t);
  }

  async function save() {
    onError(null);
    setConflict(false);
    setConflictKeys(undefined);
    if (hasWorkflowErrors(errors)) {
      setShowErrors(true);
      return;
    }
    setSubmitting(true);
    try {
      await updateRoomWorkflow(room.id, toRoomWorkflowBody(draft), revision);
    } catch (err) {
      if (isProblem(err, PROBLEM_TYPES.revisionConflict)) setConflict(true);
      else onError(failureMessage(err));
      if (isProblem(err, PROBLEM_TYPES.statusInUse)) setConflictKeys(err.keys);
      // A 403, 404 or 409 says the caller's standing or the room moved (demoted, archived, removed): show the truth.
      if (isAccessRefusal(err)) void refreshQueriesAfterMutation(queryClient, ["rooms"]);
      setSubmitting(false);
      return;
    }
    showSuccessToast(t("rooms.workflow.toast.saved"));
    // The save committed: read the new revision and re-seed (which re-keys this form away). Failing that, freeze.
    try {
      await onReseed();
    } catch {
      setStaleAfterSave(true);
      setSubmitting(false);
    }
    // The room's header and the Threads view (its status pills and the new-thread form's fields, under the shared workflow key) read the old document,
    // and a save can MOVE threads (a retired status's replacement), so the thread list and the open thread's status are stale too.
    await refreshQueriesAfterMutation(queryClient, ["rooms", "detail", room.id], ["rooms", "workflow", room.id], ["rooms", "threads", room.id]);
  }

  async function reload() {
    setReloading(true);
    try {
      await onReseed();
    } catch (err) {
      onError(loadErrorMessage(err, t));
      setReloading(false);
    }
  }

  // Locked while a save is in flight (an edit made then would be neither sent nor kept) and after a save whose re-read failed.
  const locked = staleAfterSave || submitting;
  return (
    <>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
        noValidate
      >
        <Stack gap="lg">
          {room.archived && (
            <Alert color="gray" variant="light" role="note">
              {t("rooms.workflow.archivedNote")}
            </Alert>
          )}
          {!room.archived && !editable && (
            <Alert color="gray" variant="light" role="note">
              {t("rooms.workflow.readOnlyNote")}
            </Alert>
          )}
          <Paper
            ref={groupRef}
            tabIndex={-1}
            className="mantine-focus-auto"
            withBorder
            p="xl"
            role="group"
            aria-label={t("rooms.workflow.groupAria")}
            aria-describedby={hintId}
          >
            <Stack gap="lg">
              <Text size="sm" c="dimmed" id={hintId}>
                {t("rooms.workflow.hint")}
              </Text>
              {editable ? (
                // A disabled fieldset turns every control inside it off at once: the save in flight and the freeze share it.
                <fieldset disabled={locked} style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
                  <WorkflowEditor draft={draft} onChange={setDraft} labelColumns={1} errors={showErrors ? errors : undefined} conflictKeys={conflictKeys} />
                </fieldset>
              ) : (
                <RoomWorkflowView draft={seed} />
              )}
            </Stack>
          </Paper>
          {conflict && <RevisionConflictAlert onReload={() => void reload()} reloading={reloading} />}
          {staleAfterSave && (
            <Alert color="orange" variant="light" title={t("rooms.workflow.staleTitle")}>
              {t("rooms.workflow.stale")}
              <div>
                <Button mt="sm" size="xs" variant="default" onClick={() => void reload()} loading={reloading}>
                  {t("workflows.conflict.reload")}
                </Button>
              </div>
            </Alert>
          )}
          {editable ? (
            <RegistryEditorActions
              error={invalid ? t("rooms.workflow.invalid") : error}
              submitting={submitting}
              isEdit
              onClose={guard.requestCancel}
              gap="sm"
              submitDisabled={!dirty || staleAfterSave}
            />
          ) : (
            // A refusal that turned the editor read-only (demoted or archived behind the page) still says why.
            error && <FormErrorAlert>{error}</FormErrorAlert>
          )}
        </Stack>
      </form>
      <ConfirmActionModal {...guard.confirmProps} />
    </>
  );
}
