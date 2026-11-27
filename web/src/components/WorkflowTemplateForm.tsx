import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Box, Paper, Stack, Text } from "@mantine/core";
import { useForm } from "@mantine/form";
import { ApiError } from "../api/http";
import { useDiscardGuard } from "../hooks/useDiscardGuard";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { PROBLEM_TYPES, isProblem } from "../utils/problems";
import { loadErrorMessage } from "../utils/saveError";
import {
  MAX_TEMPLATE_DESCRIPTION_LENGTH,
  MAX_TEMPLATE_NAME_LENGTH,
  isTemplateDirty,
  templateMetaValidation,
  templateSaveErrorMessage,
  type TemplateMeta,
  type TemplateSeed,
} from "../utils/workflowTemplateForm";
import { hasWorkflowErrors, toTemplateBody, validateWorkflowDraft, type WorkflowTemplateBody } from "../utils/workflowForm";
import ConfirmActionModal from "./ConfirmActionModal";
import RegistryEditorActions from "./RegistryEditorActions";
import RegistryMetadataFields from "./RegistryMetadataFields";
import RevisionConflictAlert from "./RevisionConflictAlert";
import WorkflowEditor from "./WorkflowEditor";
import classes from "./ManagementSurface.module.css";

/**
 * The create and edit form of a workflow template: the registry text plus the two-language workflow editor. It owns the draft
 * (seeded ONCE from `seed`: a background refetch never rebases it, so a page re-seeds by changing this component's `key`), the
 * dirty check against that seed, the before-unload and Cancel guards and the save errors. The page owns the request.
 *
 * `onSubmit` sends the body and throws on failure. A `409 revision-conflict` (edit only) keeps the draft and offers Reload,
 * which is the page's `onReload` (refetch and re-seed); any other 409 is the name clash. Save is disabled while an edit is clean.
 */
export default function WorkflowTemplateForm({
  seed,
  isEdit,
  backTo,
  onSubmit,
  onReload,
}: {
  seed: TemplateSeed;
  isEdit: boolean;
  backTo: string;
  onSubmit: (body: WorkflowTemplateBody) => Promise<void>;
  onReload?: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const form = useForm<TemplateMeta>({
    initialValues: { name: seed.name, description: seed.description },
    validate: templateMetaValidation(t),
  });
  const [draft, setDraft] = useState(seed.draft);
  const [showErrors, setShowErrors] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [reloading, setReloading] = useState(false);
  // The conflict alert's Reload: set by a `revision-conflict` (edit only), cleared by the next save.
  const [conflictReload, setConflictReload] = useState<{ run: () => Promise<void> } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const hintId = useId();

  const dirty = isTemplateDirty(form.values, draft, seed);
  const guard = useDiscardGuard({ isDirty: dirty, to: backTo });

  function showInvalid() {
    setShowErrors(true);
    setError(t("workflows.templates.invalid"));
  }

  async function save(values: TemplateMeta) {
    setError(null);
    setConflictReload(null);
    if (hasWorkflowErrors(validateWorkflowDraft(draft))) return showInvalid();
    setSubmitting(true);
    try {
      await onSubmit(toTemplateBody(values, draft));
    } catch (err) {
      // Only an edit has a revision to conflict on (and a Reload to offer).
      if (onReload && isProblem(err, PROBLEM_TYPES.revisionConflict)) setConflictReload({ run: onReload });
      else if (err instanceof ApiError && err.status === 409) form.setFieldError("name", t("workflows.templates.saveConflict"));
      else setError(templateSaveErrorMessage(err, t));
      setSubmitting(false);
    }
  }

  async function reload(reseed: () => Promise<void>) {
    setReloading(true);
    try {
      await reseed();
    } catch (err) {
      // The template was deleted since the form was seeded: say so, rather than a generic load failure.
      setError(err instanceof ApiError && err.status === 404 ? t("workflows.templates.notFound") : loadErrorMessage(err, t));
    } finally {
      setReloading(false);
    }
  }

  return (
    <>
      <form onSubmit={form.onSubmit(save, showInvalid)} noValidate>
        <Stack gap="lg">
          <Paper withBorder maw={FORM_MAX_WIDTH} className={classes.formCard}>
            <Stack>
              <RegistryMetadataFields
                nameMaxLength={MAX_TEMPLATE_NAME_LENGTH}
                descriptionMaxLength={MAX_TEMPLATE_DESCRIPTION_LENGTH}
                descriptionLength={form.values.description.length}
                nameInputProps={form.getInputProps("name")}
                descriptionInputProps={form.getInputProps("description")}
              />
            </Stack>
          </Paper>
          <Paper withBorder role="group" aria-label={t("workflows.templates.workflowGroup")} aria-describedby={hintId} className={`${classes.formCard} ${classes.workflowCard}`}>
            <Stack gap="lg">
              <Text fw={600}>{t("workflows.templates.workflowGroup")}</Text>
              <Text size="sm" c="dimmed" id={hintId}>
                {t("workflows.templates.labelsHint")}
              </Text>
              <WorkflowEditor draft={draft} onChange={setDraft} labelColumns={2} errors={showErrors ? validateWorkflowDraft(draft) : undefined} />
            </Stack>
          </Paper>
          {conflictReload && <RevisionConflictAlert onReload={() => void reload(conflictReload.run)} reloading={reloading} />}
          <Box className={classes.workflowFooter}>
            <RegistryEditorActions
              error={error}
              submitting={submitting}
              isEdit={isEdit}
              onClose={guard.requestCancel}
              gap="sm"
              submitDisabled={isEdit && !dirty}
            />
          </Box>
        </Stack>
      </form>
      <ConfirmActionModal {...guard.confirmProps} />
    </>
  );
}
