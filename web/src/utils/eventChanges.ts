import type { TFunction } from "i18next";

/**
 * One body line for a field that moved from one value to another (Toadie's, ported and cut down to the two-sided case a thread's history
 * records: `from` and `to` are display text, an absent side means unset), or null when neither side carries a value — the sentence above
 * already names the field, and a "Severity: changed" line would only repeat it. `label` is the already-localized name of the field.
 */
export function changeLine(label: string, from: string | undefined, to: string | undefined, t: TFunction): string | null {
  if (from && to) return t("common.history.change.fromTo", { label, from, to });
  if (to) return t("common.history.change.set", { label, to });
  if (from) return t("common.history.change.cleared", { label });
  return null;
}
