// Tag-registry API (`/api/v1/tag-categories`, 1.2.0) — the ADMIN-curated categories of tags (Hammurabi's, ported). Any authenticated
// user reads them (the Tags page now, the thread tag picker and filter later); ADMIN writes them. Thin endpoint wrappers: transport in
// ./http, types from ./schema. The replace and the delete are guarded by the revision the admin loaded the category at (a stale one is
// the typed `revision-conflict` 409, see utils/problems.ts); the replace answers 204, so the caller re-reads for the new revision.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components } from "./schema";

export type TagCategory = components["schemas"]["TagCategory"];
export type TagCategoryBody = components["schemas"]["TagCategoryRequest"];

type TagCategoryPage = components["schemas"]["TagCategoryPage"];

/** The server's page-size maximum: the registry holds at most 100 categories, so one page of this size is normally the whole of it. */
const TAG_CATEGORY_PAGE_SIZE = 100;

/**
 * The WHOLE registry, in the server's name order: pages of [TAG_CATEGORY_PAGE_SIZE], and while `total` says more exist the next page
 * is fetched (the cap makes that a safety net, not the usual path). An empty page, or as many categories as `total` says, ends the
 * walk, so a registry that shrinks meanwhile never loops; a category that shifts between pages is kept once.
 */
export async function getTagCategories(): Promise<TagCategory[]> {
  const byId = new Map<number, TagCategory>();
  for (let page = 1; ; page++) {
    const params = buildQuery({ page, pageSize: TAG_CATEGORY_PAGE_SIZE });
    const body = await jsonRequest<TagCategoryPage>(`/api/v1/tag-categories?${params}`);
    for (const category of body.items) byId.set(category.id, category);
    if (body.items.length === 0 || byId.size >= body.total) return [...byId.values()];
  }
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
