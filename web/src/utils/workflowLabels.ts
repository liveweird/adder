import type { WorkflowTemplateResponse } from "../api/workflowTemplates";
import type { SupportedLanguage } from "../i18n";

type Label = WorkflowTemplateResponse["statuses"][number]["label"];

/** The label for one language, else the EN one: a blank translation counts as missing (the fallback is per label). */
function resolvedLabel(label: Label, language: SupportedLanguage): string {
  return label[language]?.trim() ? label[language] : label.en;
}

/**
 * What a room created in `language` would call each of a template's labels — the server's rule (`rooms/RoomWorkflow.kt`
 * `resolvedLabels`): the per-label fallback to EN can make two labels of one list collide case-insensitively (one translated to a
 * word another keeps in English), and the whole list then falls back to EN. Statuses and fields are separate lists.
 */
export function resolvedLabels(labels: ReadonlyArray<Label>, language: SupportedLanguage): string[] {
  const resolved = labels.map((label) => resolvedLabel(label, language));
  const distinct = new Set(resolved.map((label) => label.toLowerCase())).size === resolved.length;
  return distinct ? resolved : labels.map((label) => label.en);
}
