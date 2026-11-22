import type { TFunction } from "i18next";
import type { MessageResponse } from "../api/messages";
import type { QuoteDraft } from "../hooks/useThreadDrafts";
import { messageExcerpt } from "./messageExcerpt";

/**
 * A picked quote whose message is gone (1.3 A7): the author stays ("Quoting {author}"), the text goes. The words are blanked, not merely hidden, so
 * the line, the status line and the kept draft show nothing of what a moderator removed (0.3 D2: a delete is effective in every quote).
 */
export function blankQuote(quote: QuoteDraft): QuoteDraft {
  return { ...quote, body: "", mentions: [], groupMentions: [], unavailable: true };
}

/**
 * What the already-loaded message list says about the picked message, applied to the draft (no request of its own): a deleted message blanks
 * the quote, an edited one refreshes its text (and the people it mentions) from the loaded message. The same object comes back when nothing
 * changes, so a caller that stores the result can compare by identity. A quote already blanked stays blanked; a message that is not loaded says nothing.
 */
export function reconcileQuote(quote: QuoteDraft, loaded: MessageResponse | undefined): QuoteDraft {
  if (quote.unavailable === true || loaded === undefined) return quote;
  if (loaded.deleted || loaded.body === null) return blankQuote(quote);
  if (loaded.body === quote.body) return quote;
  return { ...quote, authorName: loaded.authorName, body: loaded.body, mentions: loaded.mentions, groupMentions: loaded.groupMentions };
}

/** The line a composer shows (and announces) for the quote: the excerpt of its text, or the "no longer available" words once it is blanked. */
export function quoteDraftExcerpt(quote: QuoteDraft, t: TFunction): string {
  return quote.unavailable === true ? t("threads.quote.unavailable") : messageExcerpt(quote);
}
