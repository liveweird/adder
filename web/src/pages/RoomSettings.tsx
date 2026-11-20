import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { Alert, Button, Group, Paper, Stack, Text, Title } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { IconArchive, IconArchiveOff, IconTrash } from "@tabler/icons-react";
import { useAdmin } from "../auth";
import { ApiError } from "../api/http";
import { archiveRoom, deleteRoom, unarchiveRoom, updateRoom, type RoomResponse } from "../api/rooms";
import ConfirmActionModal from "../components/ConfirmActionModal";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import FormErrorAlert from "../components/FormErrorAlert";
import RegistryEditorActions from "../components/RegistryEditorActions";
import RegistryMetadataFields from "../components/RegistryMetadataFields";
import RevisionConflictAlert from "../components/RevisionConflictAlert";
import RoomPage from "../components/RoomPage";
import { useBeforeUnloadGuard } from "../hooks/useBeforeUnloadGuard";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { PROBLEM_TYPES, isProblem } from "../utils/problems";
import { canManageRoom } from "../utils/roomAccess";
import {
  MAX_ROOM_DESCRIPTION_LENGTH,
  MAX_ROOM_NAME_LENGTH,
  isRoomDetailsDirty,
  roomDeleteErrorMessage,
  roomDetailsValidation,
  roomLifecycleErrorMessage,
  roomSaveErrorMessage,
  toRoomDetails,
  toRoomUpdateBody,
  type RoomDetails,
} from "../utils/roomForm";
import { roomsPath } from "../utils/roomLinks";
import { showSuccessToast } from "../utils/toast";
import { roomKeys } from "../utils/roomKeys";

/**
 * The details form of one room. Seeded from the room it is mounted with and keyed by the room alone, so a change of the room's data
 * under it (a live update, the caller's own save, an archive) never re-mounts it. The seed rule is the thread details form's: when the
 * server's values move, the draft follows silently while the form is clean, while it already equals them (the caller's own save) and while
 * the form is read-only (nothing to protect); otherwise the draft is kept and a "changed meanwhile" notice offers Reload.
 */
function DetailsForm({ room, canManage, error, onError }: { room: RoomResponse; canManage: boolean; error: string | null; onError: (message: string | null) => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const server = toRoomDetails(room);
  const [seed, setSeed] = useState(server);
  const form = useForm<RoomDetails>({ initialValues: seed, validate: roomDetailsValidation(t) });
  const [submitting, setSubmitting] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  // Archived or not the caller's to change: the details are shown, not editable. (A save in flight makes the fields read-only too, below.)
  const readOnly = room.archived || !canManage;
  const dirty = isRoomDetailsDirty(form.values, seed);
  const moved = isRoomDetailsDirty(server, seed);
  const draftIsServer = !isRoomDetailsDirty(form.values, server);
  useBeforeUnloadGuard(dirty);

  /** Makes `values` (the server's by default) both the seed and the draft; Cancel resets to them. */
  function adopt(values: RoomDetails = server) {
    setSeed(values);
    form.setInitialValues(values);
    form.setValues(values);
  }
  if (!(draftIsServer && !moved) && (readOnly || !dirty || draftIsServer)) adopt();
  // Not while the save is in flight: its own refetch moves the room before the save ends.
  const showMoved = !submitting && !readOnly && moved && dirty && !draftIsServer;

  async function save(values: RoomDetails) {
    onError(null);
    setSubmitting(true);
    try {
      const body = toRoomUpdateBody(values);
      await updateRoom(room.id, body);
      // What was sent IS the room now: the seed, so the refetch of the caller's own save never counts as the room having moved.
      adopt({ name: body.name, description: body.description ?? "" });
      showSuccessToast(t("rooms.toast.saved"));
      await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    } catch (err) {
      if (isProblem(err, PROBLEM_TYPES.roomArchived)) {
        onError(t("rooms.settings.saveArchived"));
        // The room was archived behind this page: read it again, so the form turns read-only.
        void queryClient.invalidateQueries({ queryKey: ["rooms"] });
      } else if (err instanceof ApiError && err.status === 409) {
        form.setFieldError("name", t("rooms.settings.saveConflict"));
      } else {
        onError(roomSaveErrorMessage(err, t));
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form ref={formRef} onSubmit={form.onSubmit(save)} noValidate>
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        <Stack>
          <Title order={3} size="h4">
            {t("rooms.settings.detailsTitle")}
          </Title>
          {room.archived && (
            <Alert color="gray" variant="light" role="note">
              {t("rooms.settings.archivedNote")}
            </Alert>
          )}
          {!room.archived && !canManage && (
            <Alert color="gray" variant="light" role="note">
              {t("rooms.settings.readOnlyNote")}
            </Alert>
          )}
          <RegistryMetadataFields
            nameMaxLength={MAX_ROOM_NAME_LENGTH}
            descriptionMaxLength={MAX_ROOM_DESCRIPTION_LENGTH}
            descriptionLength={form.values.description.length}
            nameInputProps={{ ...form.getInputProps("name"), readOnly: readOnly || submitting }}
            descriptionInputProps={{ ...form.getInputProps("description"), readOnly: readOnly || submitting }}
          />
          {showMoved && (
            <RevisionConflictAlert
              title={t("rooms.settings.moved.title")}
              message={t("rooms.settings.moved.message")}
              onReload={() => {
                onError(null);
                adopt();
                formRef.current?.querySelector("input")?.focus();
              }}
            />
          )}
          {!readOnly && <RegistryEditorActions error={error} submitting={submitting} isEdit onClose={() => {
              form.reset();
              onError(null);
            }} gap="sm" submitDisabled={!dirty} />}
          {/* A refusal that turns the form read-only (the room was archived behind the page) has no action row to show it in, so it shows here. */}
          {readOnly && error && <FormErrorAlert>{error}</FormErrorAlert>}
        </Stack>
      </Paper>
    </form>
  );
}

/** Archive/Unarchive (the room's manager) and Delete (an ADMIN): the lifecycle controls under the details. */
function Lifecycle({ room, admin }: { room: RoomResponse; admin: boolean }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);

  const toggle = useMutation({
    mutationFn: () => (room.archived ? unarchiveRoom(room.id) : archiveRoom(room.id)),
    onSuccess: async () => {
      setConfirming(false);
      showSuccessToast(t(room.archived ? "rooms.toast.unarchived" : "rooms.toast.archived"));
      await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    },
    // The modal closes either way; the failure shows inline under the button.
    onError: () => setConfirming(false),
  });

  const remove = useDeleteConfirm<RoomResponse>({
    mutationFn: (target) => deleteRoom(target.id),
    onSuccess: async (target) => {
      // Leave first: the room's own queries would only answer 404 from here on.
      navigate(roomsPath, { replace: true });
      queryClient.removeQueries({ queryKey: roomKeys.detail(target.id) });
      queryClient.removeQueries({ queryKey: roomKeys.workflow(target.id) });
      await refreshQueriesAfterMutation(queryClient, ["rooms"]);
    },
    successMessage: t("rooms.toast.deleted"),
  });

  const archivedKey = room.archived ? "unarchive" : "archive";
  return (
    <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
      <Stack>
        <Title order={3} size="h4">
          {t("rooms.settings.lifecycleTitle")}
        </Title>
        {toggle.isError && (
          <FormErrorAlert>
            {roomLifecycleErrorMessage(toggle.error, t)}
          </FormErrorAlert>
        )}
        <Group justify="space-between" wrap="nowrap" align="flex-start">
          <Text size="sm">{t(`rooms.settings.${archivedKey}Hint`)}</Text>
          <Button
            variant="default"
            leftSection={room.archived ? <IconArchiveOff size={16} /> : <IconArchive size={16} />}
            onClick={() => {
              toggle.reset();
              setConfirming(true);
            }}
          >
            {t(`rooms.settings.${archivedKey}Action`)}
          </Button>
        </Group>
        {admin && (
          <Group justify="space-between" wrap="nowrap" align="flex-start">
            <Text size="sm">{t("rooms.settings.deleteHint")}</Text>
            <Button color="red" variant="light" leftSection={<IconTrash size={16} />} onClick={() => remove.requestDelete(room)}>
              {t("rooms.settings.deleteAction")}
            </Button>
          </Group>
        )}
      </Stack>
      <ConfirmActionModal
        opened={confirming}
        onClose={() => setConfirming(false)}
        title={t(`rooms.settings.${archivedKey}Title`)}
        message={t(`rooms.settings.${archivedKey}Body`, { name: room.name })}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t(`rooms.settings.${archivedKey}Action`)}
        confirmColor="adder"
        onConfirm={() => toggle.mutate()}
        loading={toggle.isPending}
      />
      <ConfirmDeleteModal
        confirm={remove}
        title={t("rooms.settings.deleteTitle")}
        errorTitle={t("rooms.settings.deleteFailed")}
        errorMessage={(error) => roomDeleteErrorMessage(error, t)}
        body={(target) => t("rooms.settings.deleteBody", { name: target.name })}
      />
    </Paper>
  );
}

/** The page body of one loaded room. It owns the save error, which must outlive the action row that shows it (see `DetailsForm`). */
function SettingsBody({ room }: { room: RoomResponse }) {
  const admin = useAdmin();
  const [error, setError] = useState<string | null>(null);
  const canManage = canManageRoom(room, admin);
  return (
    <>
      <DetailsForm
        key={room.id}
        room={room}
        canManage={canManage}
        error={error}
        onError={setError}
      />
      {canManage && <Lifecycle room={room} admin={admin} />}
    </>
  );
}

/**
 * `/rooms/:id/settings`: the room's name and description (its OWNER or an ADMIN edits them; everyone else reads them), then the
 * lifecycle: archive or unarchive (the same two), and an ADMIN's delete. An archived room's details are read-only until it is
 * unarchived. The details form follows the room's values while it is clean and keeps a dirty draft (with a notice) when they change under it.
 */
export default function RoomSettings() {
  const { id } = useParams();
  return <RoomPage idParam={id} tab="settings">{(room) => <SettingsBody room={room} />}</RoomPage>;
}
