import { Alert, Button } from "@mantine/core";
import { useTranslation } from "react-i18next";

/**
 * The inline answer to `409 revision-conflict` on a whole-document save: an orange (warning) alert that says the draft is KEPT and
 * offers Reload, which the page wires to refetching and re-seeding. A background refetch never rebases a draft; this is the
 * one way the newer version replaces it, and only on the user's click. The words are the workflow's by default; a thread's details editor
 * passes its own (already translated) `title`, `message` and `reloadLabel`.
 */
export default function RevisionConflictAlert({
  onReload,
  reloading = false,
  title,
  message,
  reloadLabel,
}: {
  onReload: () => void;
  reloading?: boolean;
  title?: string;
  message?: string;
  reloadLabel?: string;
}) {
  const { t } = useTranslation();
  return (
    <Alert color="orange" variant="light" title={title ?? t("workflows.conflict.title")}>
      {message ?? t("workflows.conflict.message")}
      <div>
        <Button mt="sm" size="xs" variant="default" onClick={onReload} loading={reloading}>
          {reloadLabel ?? t("workflows.conflict.reload")}
        </Button>
      </div>
    </Alert>
  );
}
