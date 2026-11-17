import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import type { WorkflowTemplateResponse } from "../api/workflowTemplates";
import { hasWorkflowErrors, toTemplateBody, validateWorkflowDraft, withLabel } from "./workflowForm";
import { blankTemplateSeed, isTemplateDirty, templateMetaValidation, templateSaveErrorMessage, templateSeedOf } from "./workflowTemplateForm";

const t = i18n.t.bind(i18n);

const TEMPLATE: WorkflowTemplateResponse = {
  id: 4,
  name: "Incident",
  description: null,
  statuses: [
    { key: "open", label: { en: "Open", pl: "Otwarte" }, colour: "RED", category: "OPEN", initial: true },
    { key: "done", label: { en: "Done" }, colour: "TEAL", category: "CLOSED", initial: false },
  ],
  fields: [],
  revision: 3,
  createdAt: 1,
  updatedAt: 2,
};

describe("blankTemplateSeed", () => {
  test("is a valid skeleton with an initial Open status and a Closed one, so Create needs only a name", () => {
    const seed = blankTemplateSeed();
    expect(hasWorkflowErrors(validateWorkflowDraft(seed.draft))).toBe(false);
    expect(toTemplateBody({ name: "X", description: "" }, seed.draft)).toEqual({
      name: "X",
      description: null,
      statuses: [
        { key: "open", label: { en: "Open" }, colour: "BLUE", category: "OPEN", initial: true },
        { key: "done", label: { en: "Done" }, colour: "TEAL", category: "CLOSED", initial: false },
      ],
      fields: [],
    });
    expect(seed.draft.statuses.every((s) => !s.persisted)).toBe(true);
  });
});

describe("templateSeedOf", () => {
  test("maps a template to its registry text and a persisted draft; a null description is blank", () => {
    const seed = templateSeedOf(TEMPLATE);
    expect(seed.name).toBe("Incident");
    expect(seed.description).toBe("");
    expect(seed.draft.statuses.map((s) => [s.key, s.persisted, s.labels.pl])).toEqual([["open", true, "Otwarte"], ["done", true, ""]]);
    expect(templateSeedOf({ ...TEMPLATE, description: "Notes" }).description).toBe("Notes");
  });
});

describe("isTemplateDirty", () => {
  const seed = templateSeedOf(TEMPLATE);
  const meta = { name: seed.name, description: seed.description };

  test("an unchanged form is clean, whitespace around the text included", () => {
    expect(isTemplateDirty(meta, seed.draft, seed)).toBe(false);
    expect(isTemplateDirty({ name: " Incident ", description: "  " }, seed.draft, seed)).toBe(false);
  });

  test("a changed name, description or workflow is dirty, and editing back makes it clean again", () => {
    expect(isTemplateDirty({ ...meta, name: "Other" }, seed.draft, seed)).toBe(true);
    expect(isTemplateDirty({ ...meta, description: "x" }, seed.draft, seed)).toBe(true);
    const relabelled = { ...seed.draft, statuses: seed.draft.statuses.map((s, i) => (i === 0 ? withLabel(s, "pl", "Nowe") : s)) };
    expect(isTemplateDirty(meta, relabelled, seed)).toBe(true);
    const back = { ...relabelled, statuses: relabelled.statuses.map((s, i) => (i === 0 ? withLabel(s, "pl", "Otwarte") : s)) };
    expect(isTemplateDirty(meta, back, seed)).toBe(false);
  });
});

describe("templateMetaValidation", () => {
  const rules = templateMetaValidation(t);

  test("the name is 1-100 characters after trimming and the description at most 500", () => {
    expect(rules.name("")).toBe("Name must be 1–100 characters");
    expect(rules.name("   ")).toBe("Name must be 1–100 characters");
    expect(rules.name("a".repeat(101))).toBe("Name must be 1–100 characters");
    expect(rules.name("a".repeat(100))).toBeNull();
    expect(rules.description("a".repeat(501))).toBe("Description must be at most 500 characters");
    expect(rules.description("")).toBeNull();
  });
});

describe("templateSaveErrorMessage", () => {
  test("maps the statuses the registry answers and falls back for anything else", () => {
    const status = (n: number) => templateSaveErrorMessage(new ApiError(n, {}), t);
    expect(status(403)).toBe("Only administrators can edit workflow templates");
    expect(status(404)).toBe("This workflow template no longer exists.");
    expect(status(400)).toBe("The server rejected this template. Review its contents and try again.");
    expect(status(500)).toBe(t("common.error.saveFailedStatus", { status: 500 }));
    expect(templateSaveErrorMessage(new TypeError("offline"), t)).toBe(t("common.error.saveFailedNetwork"));
  });
});
