import type { TFunction } from "i18next";
import type { TagCategory, TagCategoryBody } from "../api/tagCategories";
import { descriptionRule, nameRule } from "./formRules";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { saveErrorMessage } from "./saveError";

// Server limits (tags/TagCategory.kt) mirrored client-side.
export const MAX_CATEGORY_NAME_LENGTH = 60;
export const MAX_CATEGORY_DESCRIPTION_LENGTH = 500;
const MAX_CATEGORY_TAGS = 100;
const MAX_TAG_NAME_LENGTH = 40;

/** A tag starts with a letter or digit and goes on with those, spaces and `. _ / + # : -` (the server's grammar; no comma). */
const TAG_GRAMMAR = /^[\p{L}\p{N}][\p{L}\p{N} ._/+#:-]*$/u;

export type TagCategoryFormValues = {
  name: string;
  description: string;
  tags: string[];
};

export const EMPTY_TAG_CATEGORY_FORM: TagCategoryFormValues = { name: "", description: "", tags: [] };

/** The server's own normal form of a tag name: trimmed, whitespace runs collapsed to one space, lower-cased. */
export function normalizeTag(raw: string): string {
  return raw.trim().replace(/\s+/g, " ").toLowerCase();
}

export function toTagCategoryFormValues(category: TagCategory): TagCategoryFormValues {
  return { name: category.name, description: category.description ?? "", tags: [...category.tags] };
}

/** The wire body: a blank description travels as null; the tag names are sent in their normal form. */
export function toTagCategoryBody(values: TagCategoryFormValues): TagCategoryBody {
  const description = values.description.trim();
  return {
    name: values.name.trim(),
    description: description === "" ? null : description,
    tags: values.tags.map(normalizeTag),
  };
}

/** Validation rules for the create/edit modal (mirrors the server's checks). */
export function tagCategoryFormValidation(t: TFunction) {
  return {
    name: nameRule(t, "tags.validation.nameLength", MAX_CATEGORY_NAME_LENGTH),
    description: descriptionRule(t, "tags.validation.descriptionLength", MAX_CATEGORY_DESCRIPTION_LENGTH),
    tags: (values: string[]) => {
      if (values.length === 0) return t("tags.validation.tagsRequired");
      if (values.length > MAX_CATEGORY_TAGS) return t("tags.validation.tagsCount", { max: MAX_CATEGORY_TAGS });
      const names = values.map(normalizeTag);
      const bad = names.find((tag) => tag.length < 1 || tag.length > MAX_TAG_NAME_LENGTH || !TAG_GRAMMAR.test(tag));
      if (bad !== undefined) return t("tags.validation.tag", { tag: bad, max: MAX_TAG_NAME_LENGTH });
      return new Set(names).size === names.length ? null : t("tags.validation.tagsDuplicate");
    },
  };
}

/**
 * The registry's own 429: its writes share the room lock's problem type (`room-busy`, the table lock stayed held by another writer for about 3 s,
 * nothing was written), whose shared words talk about "the room" - the Tags page says it of the registry instead (1.2 S7).
 */
function registryBusy(err: unknown, t: TFunction): string | null {
  return isProblem(err, PROBLEM_TYPES.roomBusy) ? t("tags.registryBusy") : null;
}

/** The category save's fixed vocabulary: the 409 covers three causes (the name is taken, a tag belongs to another category, or the category moved on). */
export function tagCategorySaveErrorMessage(err: unknown, t: TFunction): string {
  return registryBusy(err, t) ?? saveErrorMessage(err, t, {
    forbidden: "tags.saveForbidden",
    // A stale revision is its own 409 (typed); every other one is a name or a tag already in use.
    conflict: isProblem(err, PROBLEM_TYPES.revisionConflict) ? "tags.saveStale" : "tags.saveConflict",
    invalid: "tags.saveInvalid",
    notFound: "tags.saveGone",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}

/** The category delete's fixed vocabulary (the confirm dialog's error line). */
export function tagCategoryDeleteErrorMessage(err: unknown, t: TFunction): string {
  return (
    registryBusy(err, t) ??
    saveErrorMessage(err, t, {
      forbidden: "tags.saveForbidden",
      notFound: "tags.saveGone",
      conflict: "tags.deleteStale",
      failedStatus: "common.error.actionFailedStatus",
      failed: "common.error.actionFailed",
    })
  );
}
