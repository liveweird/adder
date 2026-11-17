import { useState } from "react";
import { Alert, Button, Group, Modal, Select, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { displayName, type StatusDraft } from "../utils/workflowForm";

/**
 * "N threads are in X. Move them to:" — asked before a persisted status that threads use is removed (and again when the server
 * answers `409 status-in-use`). `candidates` are the remaining PERSISTED statuses of the SAME category (a workflow edit never
 * closes or reopens a thread behind its history; see `replacementCandidates`); `onConfirm` hands back the chosen status's draft id. `status` is null while closed; the last one
 * stays on screen through the close transition.
 */
export default function RetireStatusModal({
  status,
  candidates,
  onConfirm,
  onCancel,
}: {
  status: StatusDraft | null;
  candidates: StatusDraft[];
  onConfirm: (replacementId: string) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [shown, setShown] = useState(status);
  if (status && status !== shown) setShown(status);
  const [chosen, setChosen] = useState<string | null>(null);
  const name = shown ? displayName(shown) : "";
  const count = shown?.threadCount ?? 0;
  const close = () => {
    setChosen(null);
    onCancel();
  };
  return (
    <Modal
      opened={status !== null}
      onClose={close}
      title={t("workflows.retire.title")}
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      centered
    >
      <Stack gap="md">
        <Text>{count > 0 ? t("workflows.retire.message", { count, status: name }) : t("workflows.retire.messageUnknown", { status: name })}</Text>
        {candidates.length === 0 ? (
          <Alert color="orange" variant="light">
            {t("workflows.retire.none")}
          </Alert>
        ) : (
          <Select
            label={t("workflows.retire.target")}
            data={candidates.map((c) => ({ value: c.id, label: displayName(c) }))}
            value={chosen}
            onChange={setChosen}
            allowDeselect={false}
            data-autofocus
          />
        )}
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={close}>
            {t("common.action.cancel")}
          </Button>
          <Button
            color="red"
            disabled={chosen === null}
            onClick={() => {
              // Disabled until a target is picked, so `chosen` is set here.
              onConfirm(chosen as string);
              setChosen(null);
            }}
          >
            {t("workflows.retire.confirm")}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
