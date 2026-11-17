import { foldDiacritics } from "./text";

/** The server's key length cap (`workflows/Workflow.kt` `MAX_KEY_LENGTH`). */
export const MAX_KEY_LENGTH = 40;

/**
 * A label as a workflow key candidate: accent-folded (`Triaż` becomes `triaz`), every run of characters outside `a-z0-9` one
 * hyphen, starting with a letter and ending with a letter or digit, at most `max` characters. The result matches the server's
 * grammar `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` whenever it is non-empty; a label with no Latin letter yields "".
 */
export function slugify(label: string, max = MAX_KEY_LENGTH): string {
  // Runs are already single hyphens, so the final replace only has to remove the one that may trail the cut.
  return foldDiacritics(label)
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^[^a-z]+/, "")
    .slice(0, max)
    .replace(/-$/, "");
}
