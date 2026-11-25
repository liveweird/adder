import type { TFunction } from "i18next";
import type { ThreadEvent } from "../api/threads";
import { changeLine } from "./eventChanges";

type Params = ThreadEvent["params"];

/** A recorded param, or a dash when a (newer or older) server left it out: a history line must never blank out or read "undefined". */
function param(params: Params, key: string, fallbackKey?: string): string {
  return params[key] || (fallbackKey ? params[fallbackKey] : "") || "—";
}

/**
 * The one localized sentence a history entry leads with, in the viewer's language (the server stores keys, ids and snapshots of labels and
 * names, never prose). Toadie's `describeEvent` shape: an event kind this build does not know shows its raw type, so a newer server never
 * blanks a line out. The status sentences use the labels recorded at that moment, falling back to the keys.
 */
export function describeEvent(event: ThreadEvent, t: TFunction): string {
  const p = event.params;
  const from = param(p, "fromLabel", "from");
  const to = param(p, "toLabel", "to");
  switch (event.type) {
    case "CREATED":
      return t("threads.event.created", { status: param(p, "statusLabel", "status") });
    case "TITLE_CHANGED":
      return t("threads.event.titleChanged");
    case "STATUS_CHANGED":
      return t("threads.event.statusChanged", { from, to });
    case "CLOSED":
      return t("threads.event.closed", { from, to });
    case "REOPENED":
      return t("threads.event.reopened", { from, to });
    case "STATUS_REPLACED":
      return t("threads.event.statusReplaced", { from, to });
    case "OWNER_CHANGED":
      return t("threads.event.ownerChanged", { from: param(p, "fromName", "fromId"), to: param(p, "toName", "toId") });
    case "FIELD_CHANGED":
      return t("threads.event.fieldChanged", { field: param(p, "fieldLabel", "field") });
    case "TAGS_CHANGED":
      return t("threads.event.tagsChanged");
    case "MERGED_INTO":
      return t("threads.event.mergedInto", { number: param(p, "threadNumber") });
    case "MERGED_FROM":
      return t("threads.event.mergedFrom", { number: param(p, "threadNumber") });
    default:
      // Forward-compat: an event kind this client build does not know yet shows its raw type.
      return String(event.type);
  }
}

/** The registry names a `TAGS_CHANGED` event added and removed, one line each (a side with none has no line); the server joins a side's names with ", ". */
function tagLines(p: Params, t: TFunction): string[] {
  return [
    ...(p.added ? [t("threads.event.tagsAdded", { tags: p.added })] : []),
    ...(p.removed ? [t("threads.event.tagsRemoved", { tags: p.removed })] : []),
  ];
}

/**
 * The dimmed lines under the sentence: the changed tags of a `TAGS_CHANGED`, and the old and the new value of a changed SELECT, MULTI_SELECT, USER, DATE or NUMBER field. A rename
 * and a TEXT or URL change carry no values (the server's history keeps no free text, so the sentence is all there is), nor do the other
 * kinds, nor a retired field's entry or an entry older than that rule.
 */
export function eventLines(event: ThreadEvent, t: TFunction): string[] {
  if (event.type === "TAGS_CHANGED") return tagLines(event.params, t);
  if (event.type !== "FIELD_CHANGED") return [];
  const p = event.params;
  const line = changeLine(param(p, "fieldLabel", "field"), p.from, p.to, t);
  return line === null ? [] : [line];
}
