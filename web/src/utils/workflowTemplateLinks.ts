/** The ONE place the workflow-template route family is spelled out — never hand-assemble a template URL. */
export const workflowTemplatesPath = "/workflow-templates";
export const newWorkflowTemplatePath = `${workflowTemplatesPath}/new`;
export const editWorkflowTemplatePath = (id: number) => `${workflowTemplatesPath}/${id}/edit`;
