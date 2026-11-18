import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { Alert, Group, Paper, Select, Stack, Text } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { createRoom } from "../api/rooms";
import { getWorkflowTemplate, listWorkflowTemplates } from "../api/workflowTemplates";
import ConfirmActionModal from "../components/ConfirmActionModal";
import ErrorAlert from "../components/ErrorAlert";
import PageHeader from "../components/PageHeader";
import RegistryEditorActions from "../components/RegistryEditorActions";
import RegistryMetadataFields from "../components/RegistryMetadataFields";
import StatusPill from "../components/StatusPill";
import { useDiscardGuard } from "../hooks/useDiscardGuard";
import { asSupportedLanguage } from "../i18n";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import {
  EMPTY_ROOM_FORM,
  MAX_ROOM_DESCRIPTION_LENGTH,
  MAX_ROOM_NAME_LENGTH,
  isTemplateGone,
  roomCreateErrorMessage,
  roomFormValidation,
  toRoomCreateBody,
  type RoomFormValues,
} from "../utils/roomForm";
import { roomPath, roomsPath } from "../utils/roomLinks";
import { showSuccessToast } from "../utils/toast";
import { statusHue } from "../utils/workflowColours";
import { resolvedLabels } from "../utils/workflowLabels";

/** The statuses the picked template would give the room, as pills in the language the room will be created in. */
function TemplatePreview({ templateId }: { templateId: number }) {
  const { t, i18n } = useTranslation();
  const language = asSupportedLanguage(i18n.resolvedLanguage);
  const { data, isError, error } = useQuery({ queryKey: ["workflowTemplates", "preview", templateId], queryFn: () => getWorkflowTemplate(templateId) });
  if (isError) return <ErrorAlert error={error} title={t("rooms.create.previewFailed")} />;
  if (!data) return null;
  // What the room would call them: the server's per-list resolution (a collision in the mix puts the whole list back to EN).
  const labels = resolvedLabels(data.statuses.map((status) => status.label), language);
  return (
    <Stack gap={4}>
      <Text size="sm" fw={500}>
        {t("rooms.create.previewTitle")}
      </Text>
      <Group gap="xs" role="group" aria-label={t("rooms.create.previewTitle")}>
        {data.statuses.map((status, index) => (
          <StatusPill key={status.key} color={statusHue(status.colour)} dot>
            {labels[index]}
          </StatusPill>
        ))}
      </Group>
    </Stack>
  );
}

/**
 * `/rooms/new` (any signed-in user): a name, a description and the workflow template the room copies its statuses and fields
 * from (the picker previews the statuses; the template's labels are copied in the UI language, so a Polish-speaking creator gets
 * a Polish room). The creator becomes the room's OWNER and lands on it.
 */
export default function CreateRoom() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const form = useForm<RoomFormValues>({ initialValues: EMPTY_ROOM_FORM, validate: roomFormValidation(t) });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const guard = useDiscardGuard({ isDirty: () => form.isDirty(), to: roomsPath });

  const templates = useQuery({
    queryKey: ["workflowTemplates", "picker"],
    queryFn: () => listWorkflowTemplates({ page: 1, pageSize: 100, sort: "name" }),
  });
  const noTemplates = templates.data !== undefined && templates.data.items.length === 0;

  async function submit(values: RoomFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      const room = await createRoom(toRoomCreateBody(values, Number(values.templateId), asSupportedLanguage(i18n.resolvedLanguage)));
      showSuccessToast(t("rooms.toast.created"));
      navigate(roomPath(room.id));
      await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        form.setFieldError("name", t("rooms.create.nameTaken"));
      } else {
        // An unknown or deleted template is a 400 of its own: forget the pick and re-read the list.
        if (isTemplateGone(err)) {
          form.setFieldValue("templateId", null);
          void queryClient.invalidateQueries({ queryKey: ["workflowTemplates"] });
        }
        setError(roomCreateErrorMessage(err, t));
      }
      setSubmitting(false);
    }
  }

  return (
    <Stack gap="md">
      <PageHeader title={t("rooms.createTitle")} backTo={{ to: roomsPath, label: t("rooms.backToRooms") }} />
      <form onSubmit={form.onSubmit(submit)} noValidate>
        <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
          <Stack>
            <RegistryMetadataFields
              nameMaxLength={MAX_ROOM_NAME_LENGTH}
              descriptionMaxLength={MAX_ROOM_DESCRIPTION_LENGTH}
              descriptionLength={form.values.description.length}
              nameInputProps={form.getInputProps("name")}
              descriptionInputProps={form.getInputProps("description")}
            />
            {templates.isError && <ErrorAlert error={templates.error} title={t("rooms.create.templatesFailed")} />}
            {noTemplates && (
              <Alert color="gray" variant="light" role="note">
                {t("rooms.create.noTemplates")}
              </Alert>
            )}
            <Select
              label={t("rooms.create.template")}
              description={t("rooms.create.templateHint")}
              placeholder={t("rooms.create.templatePlaceholder")}
              data={(templates.data?.items ?? []).map((template) => ({ value: String(template.id), label: template.name }))}
              allowDeselect={false}
              {...form.getInputProps("templateId")}
            />
            {form.values.templateId !== null && <TemplatePreview templateId={Number(form.values.templateId)} />}
            <RegistryEditorActions error={error} submitting={submitting} isEdit={false} onClose={guard.requestCancel} gap="sm" submitDisabled={noTemplates} />
          </Stack>
        </Paper>
      </form>
      <ConfirmActionModal {...guard.confirmProps} />
    </Stack>
  );
}
