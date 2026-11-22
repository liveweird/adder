import { useQuery } from "@tanstack/react-query";
import { getTagCategories } from "../api/tagCategories";

/**
 * One cached query for the tag registry under `["tagCategories"]` - shared by the Tags page (and, from 1.2 S7, the thread tag picker and the
 * list's tag filter) and refreshed by every registry write. The registry holds at most 100 categories (the page-size maximum), so the one helper reads it whole, page by page if it ever has to.
 */
export function useTagCategories() {
  return useQuery({ queryKey: ["tagCategories"], queryFn: getTagCategories });
}
