import type { ComboboxItem, ComboboxItemGroup } from "@mantine/core";
import type { TagCategory } from "../api/tagCategories";
import type { ThreadTag } from "../api/threads";

/** The most tags one thread carries (the server's `MAX_THREAD_TAGS`). */
export const MAX_THREAD_TAGS = 20;

/** The tag picker's options: one group per registry category, its live tags inside (the value and the label are the tag name). */
export function tagOptionGroups(categories: readonly TagCategory[]): ComboboxItemGroup<ComboboxItem>[] {
  return categories
    .filter((category) => category.tags.length > 0)
    .map((category) => ({ group: category.name, items: category.tags.map((tag) => ({ value: tag, label: tag })) }));
}

/**
 * The names the editor starts from: the thread's tags that are still LIVE. With the registry read, "live" is what the registry says now (the
 * thread's own `retired` flags can be stale: a retirement emits no live hint, 1.2 A7); until it is read, the flags are all there is. A retired tag
 * is not in the registry, so a save that kept it would be refused (400): the editor drops it from the set it sends and says so (`retiredTagNames`),
 * and the first save of the thread's tags removes it.
 */
export function liveTagNames(tags: readonly ThreadTag[], categories?: readonly TagCategory[]): string[] {
  if (categories === undefined) return tags.filter((tag) => !tag.retired).map((tag) => tag.name);
  const live = new Set(categories.flatMap((category) => category.tags));
  return tags.filter((tag) => live.has(tag.name)).map((tag) => tag.name);
}

/** The tags the thread carries that are not in the set [liveTagNames] gives (with the same registry), in the thread's order. */
export function retiredTagNames(tags: readonly ThreadTag[], categories?: readonly TagCategory[]): string[] {
  const live = new Set(liveTagNames(tags, categories));
  return tags.filter((tag) => !live.has(tag.name)).map((tag) => tag.name);
}

/**
 * The same set of names, whatever the order (the server stores a set and answers in code point order): whether the editor's draft differs
 * from what it started from, so an unchanged set does not offer a save that would write nothing.
 */
export function sameTagSet(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && new Set(a).size === a.length && a.every((name) => b.includes(name));
}
