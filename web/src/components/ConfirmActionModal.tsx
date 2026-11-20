import type { ReactNode } from "react";
import { Button, Group, Modal, Stack, Text, type MantineColor } from "@mantine/core";
import { useTranslation } from "react-i18next";
import FormErrorAlert from "./FormErrorAlert";

/**
 * The shared confirm-before-acting modal (Lettuce's, ported with the feature-flags bulk
 * actions): a message plus a neutral cancel and a confirm button whose `loading` blocks
 * cancel/close while the action runs. Labels arrive already translated — they differ per
 * flow. Row-deletion flows paired with useDeleteConfirm keep using ConfirmDeleteModal. A flow whose action can fail
 * in place passes `error` (an already-mapped string, inline under the message, never a toast) with its `errorTitle`.
 */
export default function ConfirmActionModal({
  opened,
  onClose,
  title,
  message,
  cancelLabel,
  confirmLabel,
  onConfirm,
  loading = false,
  confirmColor = "red",
  error,
  errorTitle,
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  message: ReactNode;
  cancelLabel: string;
  confirmLabel: string;
  onConfirm: () => void;
  loading?: boolean;
  confirmColor?: MantineColor;
  error?: string | null;
  errorTitle?: string;
}) {
  const { t } = useTranslation();
  return (
    <Modal closeButtonProps={{ "aria-label": t("common.action.close") }}
      opened={opened}
      onClose={() => {
        if (!loading) onClose();
      }}
      title={title}
      centered
    >
      <Stack gap="md">
        <Text>{message}</Text>
        {error && <FormErrorAlert title={errorTitle}>{error}</FormErrorAlert>}
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={onClose} disabled={loading}>
            {cancelLabel}
          </Button>
          <Button color={confirmColor} onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
