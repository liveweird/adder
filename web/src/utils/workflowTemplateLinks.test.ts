import { describe, expect, test } from "vitest";
import { editWorkflowTemplatePath, newWorkflowTemplatePath, workflowTemplatesPath } from "./workflowTemplateLinks";

describe("workflowTemplateLinks", () => {
  test("builds the list, create and edit paths", () => {
    expect(workflowTemplatesPath).toBe("/workflow-templates");
    expect(newWorkflowTemplatePath).toBe("/workflow-templates/new");
    expect(editWorkflowTemplatePath(7)).toBe("/workflow-templates/7/edit");
  });
});
