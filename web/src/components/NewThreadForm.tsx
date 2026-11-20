import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate } from "react-router-dom";
import { Alert, Button, Collapse, Group, Stack, TextInput, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import { IconChevronDown, IconChevronRight } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { createThread } from "../api/threads";
import type { RoomResponse } from "../api/rooms";
import { useDiscardGuard } from "../hooks/useDiscardGuard";
import { useThreadDrafts } from "../hooks/useThreadDrafts";
import { useRoomWorkflow } from "../hooks/useRoomWorkflow";
import { charCountDescription } from "../utils/charCount";
import { isSendShortcut } from "../utils/messageForm";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { focusThreadPaneState, roomPath, threadPath, withListParams } from "../utils/roomLinks";
import { threadStartBlock } from "../utils/threadAccess";
import {
  MAX_MESSAGE_BODY_LENGTH,
  MAX_THREAD_TITLE_LENGTH,
  isThreadDraftEmpty,
  reconcileThreadDraft,
  threadCreateErrorMessage,
  threadFormValidation,
  toThreadCreateBody,
  type ThreadFormValues,
  type WorkflowField,
} from "../utils/threadForm";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "./ConfirmActionModal";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import LoadingBlock from "./LoadingBlock";
import MentionTextarea from "./MentionTextarea";
import ThreadFieldInput from "./ThreadFieldInput";
import { roomKeys } from "../utils/roomKeys";

/**
 * The form proper, once the room's fields are known. They are read ONCE (a background re-read never adds or drops an input under a half-typed
 * draft). The draft is kept per room above the routes (`useThreadDrafts`) as it changes, so a click on a list row, a tab or "Back to threads"
 * does not lose it; coming back restores it (laid over the room's current fields), and Cancel-and-discard or a started thread clears it.
 */
function ThreadFormBody({ room, fields }: { room: RoomResponse; fields: readonly WorkflowField[] }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { search } = useLocation();
  const queryClient = useQueryClient();
  const moreId = useId();
  const drafts = useThreadDrafts();
  // Seeded once from what was kept; "dirty" is "holds something", not "differs from the seed", since the seed may itself be a kept draft.
  const form = useForm<ThreadFormValues>({ initialValues: reconcileThreadDraft(drafts.get(room.id), fields), validate: threadFormValidation(t, fields) });
  useEffect(() => {
    drafts.set(room.id, isThreadDraftEmpty(form.values) ? null : form.values);
  }, [drafts, room.id, form.values]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showMore, setShowMore] = useState(false);
  const guard = useDiscardGuard({ isDirty: () => !isThreadDraftEmpty(form.values), to: withListParams(roomPath(room.id), search) });
  const confirmDiscard = guard.confirmProps.onConfirm;
  // Required fields lead; the rest sit behind "More fields", so the common case is a title and a message.
  const required = fields.filter((field) => field.required);
  const optional = fields.filter((field) => !field.required);

  const renderField = (field: WorkflowField) => (
    <ThreadFieldInput
      key={field.key}
      roomId={room.id}
      field={field}
      draft={form.values.fields[field.key]}
      onChange={(next) => form.setFieldValue(`fields.${field.key}`, next)}
      error={form.errors[`fields.${field.key}`]}
      disabled={submitting}
    />
  );

  async function submit(values: ThreadFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      const created = await createThread(room.id, toThreadCreateBody(values, fields));
      drafts.set(room.id, null);
      showSuccessToast(t("threads.toast.started"));
      navigate(withListParams(threadPath(room.id, created.number), search), { state: focusThreadPaneState });
      await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    } catch (err) {
      setError(threadCreateErrorMessage(err, t));
      setSubmitting(false);
      // A 403, 404 or 409 says the caller's standing moved (removed, the room archived): re-read, and the pane follows the truth.
      if (isAccessRefusal(err)) await refreshQueriesAfterMutation(queryClient, ["rooms"]);
      // A 400 may mean the room's fields moved on (the message says so when it names one): re-read them (the shared workflow key), so the next visit to the form is current.
      if (err instanceof ApiError && err.status === 400) await queryClient.invalidateQueries({ queryKey: roomKeys.workflow(room.id) });
    }
  }

  // An error on a field behind the disclosure opens it, or the failure would be invisible.
  function onInvalid(errors: Record<string, unknown>) {
    if (optional.some((field) => `fields.${field.key}` in errors)) setShowMore(true);
  }

  return (
    <>
      <form onSubmit={form.onSubmit(submit, onInvalid)} noValidate>
        <Stack gap="sm">
          <TextInput
            label={t("threads.new.titleLabel")}
            required
            maxLength={MAX_THREAD_TITLE_LENGTH}
            description={charCountDescription(form.values.title.length, MAX_THREAD_TITLE_LENGTH)}
            inputWrapperOrder={["label", "input", "description", "error"]}
            disabled={submitting}
            {...form.getInputProps("title")}
          />
          <MentionTextarea
            roomId={room.id}
            label={t("threads.new.bodyLabel")}
            description={t("threads.new.bodyHint")}
            required
            autosize
            minRows={5}
            maxRows={16}
            maxLength={MAX_MESSAGE_BODY_LENGTH}
            inputWrapperOrder={["label", "input", "description", "error"]}
            disabled={submitting}
            // Ctrl/Cmd+Enter starts the thread, as it posts in the composer; Enter stays a newline (and picks, while the mention list is open).
            onKeyDown={(event) => {
              if (isSendShortcut(event) && !submitting) {
                event.preventDefault();
                void form.onSubmit(submit, onInvalid)();
              }
            }}
            {...form.getInputProps("body")}
          />
          {required.map(renderField)}
          {optional.length > 0 && (
            <>
              <Button
                variant="subtle"
                size="compact-sm"
                w="fit-content"
                leftSection={showMore ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
                aria-expanded={showMore}
                aria-controls={moreId}
                onClick={() => setShowMore(!showMore)}
              >
                {t("threads.new.moreFields")}
              </Button>
              <Collapse expanded={showMore} id={moreId}>
                <Stack gap="sm">{optional.map(renderField)}</Stack>
              </Collapse>
            </>
          )}
          {error && <FormErrorAlert>{error}</FormErrorAlert>}
          <Group justify="flex-end">
            <Button type="button" variant="default" onClick={guard.requestCancel} disabled={submitting}>
              {t("common.action.cancel")}
            </Button>
            <Button type="submit" loading={submitting}>
              {t("threads.new.submit")}
            </Button>
          </Group>
        </Stack>
      </form>
      <ConfirmActionModal
        {...guard.confirmProps}
        onConfirm={() => {
          drafts.set(room.id, null);
          confirmDiscard();
        }}
      />
    </>
  );
}

/**
 * `/rooms/:id/threads/new`, in the room view's conversation pane: a title, the opening message (Markdown, with the emoji picker) and
 * the room's fields — the required ones first, the rest behind "More fields". Members of a live room only (UX; the server answers 403
 * and 409): anyone else sees why instead of a form that could only fail. A start lands on the new thread.
 */
export default function NewThreadForm({ room }: { room: RoomResponse }) {
  const { t } = useTranslation();
  const workflow = useRoomWorkflow(room.id);
  const block = threadStartBlock(room);
  return (
    <Stack gap="md">
      <Title order={3} size="h4">
        {t("threads.new.title")}
      </Title>
      {block !== null ? (
        <Alert color="gray" variant="light" role="note">
          {t(block === "archived" ? "threads.new.archived" : "threads.new.notMember")}
        </Alert>
      ) : workflow.data ? (
        <ThreadFormBody room={room} fields={workflow.data.fields} />
      ) : workflow.isLoading ? (
        <LoadingBlock />
      ) : (
        <ErrorAlert error={workflow.error} title={t("threads.new.workflowFailed")} />
      )}
    </Stack>
  );
}
