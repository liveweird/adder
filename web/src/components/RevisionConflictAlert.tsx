import { Alert, Button } from "@mantine/core";
import { useTranslation } from "react-i18next";

/**
 * The inline answer to `409 revision-conflict` on a workflow save: an orange (warning) alert that says the draft is KEPT and
 * offers Reload, which the page wires to refetching and re-seeding. A background refetch never rebases a draft; this is the
 * one way the newer version replaces it, and only on the user's click.
 */
export default function RevisionConflictAlert({ onReload, reloading = false }: { onReload: () => void; reloading?: boolean }) {
  const { t } = useTranslation();
  return (
    <Alert color="orange" variant="light" title={t("workflows.conflict.title")}>
      {t("workflows.conflict.message")}
      <div>
        <Button mt="sm" size="xs" variant="default" onClick={onReload} loading={reloading}>
          {t("workflows.conflict.reload")}
        </Button>
      </div>
    </Alert>
  );
}
