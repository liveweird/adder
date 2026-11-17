import type { TFunction } from "i18next";
import type { WorkflowTemplateResponse } from "../api/workflowTemplates";
import { descriptionRule, nameRule } from "./formRules";
import { saveErrorMessage } from "./saveError";
import {
  isWorkflowDirty,
  newStatusDraft,
  toTemplateWorkflowDraft,
  withLabel,
  type StatusDraft,
  type WorkflowDraft,
} from "./workflowForm";

// Server limits (workflows/WorkflowTemplate.kt) mirrored client-side.
export const MAX_TEMPLATE_NAME_LENGTH = 100;
export const MAX_TEMPLATE_DESCRIPTION_LENGTH = 500;

/** What a template form is seeded with: the registry text plus the workflow draft (the baseline of the dirty check). */
export type TemplateSeed = { name: string; description: string; draft: WorkflowDraft };

export type TemplateMeta = Pick<TemplateSeed, "name" | "description">;

/** A loaded template as a form seed. Every row is persisted (read-only keys, field types). */
export function templateSeedOf(template: WorkflowTemplateResponse): TemplateSeed {
  return { name: template.name, description: template.description ?? "", draft: toTemplateWorkflowDraft(template) };
}

/**
 * The create form's starting point: a valid two-status workflow (an initial Open status and a Closed one) rather than an
 * empty editor, which would need two rows added before it could be saved. The labels are the English template text the
 * administrator edits; the keys follow them until touched.
 */
export function blankTemplateSeed(): TemplateSeed {
  const status = (label: string, partial: Partial<StatusDraft>): StatusDraft => ({ ...withLabel(newStatusDraft(), "en", label), ...partial });
  return {
    name: "",
    description: "",
    draft: {
      statuses: [status("Open", { colour: "BLUE", initial: true }), status("Done", { colour: "TEAL", category: "CLOSED" })],
      fields: [],
      retired: [],
    },
  };
}

/** Validation rules for the registry text (mirrors the server's checks); the workflow has `validateWorkflowDraft`. */
export function templateMetaValidation(t: TFunction) {
  return {
    name: nameRule(t, "workflows.templates.validation.nameLength", MAX_TEMPLATE_NAME_LENGTH),
    description: descriptionRule(t, "workflows.templates.validation.descriptionLength", MAX_TEMPLATE_DESCRIPTION_LENGTH),
  };
}

/** Dirty = the registry text or what would be sent for the workflow differs from the seed (trimmed, so edited back is clean). */
export function isTemplateDirty(meta: TemplateMeta, draft: WorkflowDraft, seed: TemplateSeed): boolean {
  return (
    meta.name.trim() !== seed.name.trim() ||
    meta.description.trim() !== seed.description.trim() ||
    isWorkflowDirty(draft, seed.draft)
  );
}

/**
 * The template save vocabulary. A 409 is the caller's: `revision-conflict` has its own alert and a name clash marks the name
 * field. A 400 here is the server disagreeing with the client-side rules.
 */
export function templateSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "workflows.templates.saveForbidden",
    notFound: "workflows.templates.saveGone",
    invalid: "workflows.templates.saveInvalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
