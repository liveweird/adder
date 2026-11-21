// Tag-registry API (`/api/v1/tag-categories`, 1.2.0) — the ADMIN-curated categories of tags (Hammurabi's, ported). Any authenticated
// user reads them (the Tags page now, the thread tag picker and filter later); ADMIN writes them. Thin endpoint wrappers: transport in
// ./http, types from ./schema. The replace and the delete are guarded by the revision the admin loaded the category at (a stale one is
// the typed `revision-conflict` 409, see utils/problems.ts); the replace answers 204, so the caller re-reads for the new revision.

import { jsonRequest, voidRequest } from "./http";
import type { components } from "./schema";

export type TagCategory = components["schemas"]["TagCategory"];
export type TagCategoryBody = components["schemas"]["TagCategoryRequest"];

export async function getTagCategories(): Promise<TagCategory[]> {
  return (await jsonRequest<components["schemas"]["TagCategoryList"]>("/api/v1/tag-categories")).items;
}

export async function createTagCategory(body: TagCategoryBody): Promise<TagCategory> {
  return jsonRequest<TagCategory>("/api/v1/tag-categories", { method: "POST", body: JSON.stringify(body) });
}

/** A full replace of the category under the revision the admin loaded it at (a stale one is the typed revision-conflict 409). */
export async function updateTagCategory(id: number, revision: number, body: TagCategoryBody): Promise<void> {
  await voidRequest(`/api/v1/tag-categories/${id}`, {
    method: "PUT",
    headers: { "X-Expected-Revision": String(revision) },
    body: JSON.stringify(body),
  });
}

/** The soft delete, under the revision the admin saw when confirming it. */
export async function deleteTagCategory(id: number, revision: number): Promise<void> {
  await voidRequest(`/api/v1/tag-categories/${id}`, { method: "DELETE", headers: { "X-Expected-Revision": String(revision) } });
}
