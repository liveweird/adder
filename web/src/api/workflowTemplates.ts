// Workflow templates API (`/api/v1/workflow-templates`) — the ADMIN-curated registry a room copies at creation.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema. The PUT is a whole-document
// replace under the REQUIRED `X-Expected-Revision` header (the revision the caller read; a stale one is a 409
// `revision-conflict`, see utils/problems.ts); it answers 204, so the caller re-reads for the new revision.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type WorkflowTemplatePage = paths["/api/v1/workflow-templates"]["get"]["responses"]["200"]["content"]["application/json"];
export type WorkflowTemplateListItem = WorkflowTemplatePage["items"][number];
export type WorkflowTemplateResponse =
  paths["/api/v1/workflow-templates/{id}"]["get"]["responses"]["200"]["content"]["application/json"];
export type WorkflowTemplateCreateBody =
  paths["/api/v1/workflow-templates"]["post"]["requestBody"]["content"]["application/json"];
export type WorkflowTemplateUpdateBody =
  paths["/api/v1/workflow-templates/{id}"]["put"]["requestBody"]["content"]["application/json"];

type WorkflowTemplateListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  name?: string;
};

export async function listWorkflowTemplates(q: WorkflowTemplateListQuery): Promise<WorkflowTemplatePage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, name: q.name });
  return jsonRequest<WorkflowTemplatePage>(`/api/v1/workflow-templates?${params}`);
}

export async function getWorkflowTemplate(id: number): Promise<WorkflowTemplateResponse> {
  return jsonRequest<WorkflowTemplateResponse>(`/api/v1/workflow-templates/${id}`);
}

export async function createWorkflowTemplate(body: WorkflowTemplateCreateBody): Promise<WorkflowTemplateResponse> {
  return jsonRequest<WorkflowTemplateResponse>("/api/v1/workflow-templates", { method: "POST", body: JSON.stringify(body) });
}

export async function updateWorkflowTemplate(
  id: number,
  body: WorkflowTemplateUpdateBody,
  expectedRevision: number,
): Promise<void> {
  await voidRequest(`/api/v1/workflow-templates/${id}`, {
    method: "PUT",
    headers: { "X-Expected-Revision": String(expectedRevision) },
    body: JSON.stringify(body),
  });
}

export async function deleteWorkflowTemplate(id: number): Promise<void> {
  await voidRequest(`/api/v1/workflow-templates/${id}`, { method: "DELETE" });
}
