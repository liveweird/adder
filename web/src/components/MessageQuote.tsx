import { Link as RouterLink, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Group, Text, UnstyledButton, VisuallyHidden } from "@mantine/core";
import { IconQuote } from "@tabler/icons-react";
import type { MessageQuoteSummary } from "../api/messages";
import { quoteExcerpt } from "../utils/messageExcerpt";
import { listRouteSearch, messageJumpPath } from "../utils/roomLinks";
import { parseFocus } from "../utils/threadListParams";
import classes from "./MessageQuote.module.css";

/**
 * The message a reply quotes (1.3), above the reply's text: ONE router link holding the quote icon, the author's name in bold (text: a link
 * cannot hold a link, and the author's profile is one click from the message itself), "(edited)" when the quoted message was rewritten after it
 * was posted (a quoted author can change the words under someone's "Agreed": the mark is in words, never colour alone) and one clamped line
 * of the quoted message's excerpt, or the italic "The quoted message was deleted." (a deleted quote carries no text). The excerpt is
 * `quoteExcerpt`: plain text, never Markdown, never HTML. The quote shows the message as it reads NOW (the server reads it live, and the
 * conversation re-reads when the quoted message changes: `message.edited` and `message.deleted` hints).
 *
 * The link is the jump of the pin strip's "Show in thread": `messageJumpPath` with the list's keys and the focus view kept, so the
 * conversation opens the quoted message directly with its surrounding context (a deleted one opens at its tombstone). `onShow` runs when it is followed, so the pane can run the jump again when the URL already names that message. **Its accessible name is
 * its content** (no `aria-label`: axe's `label-content-name-mismatch`, WCAG 2.5.3, wants the visible words inside the name): a visually hidden
 * lead-in, "Quoted message by", then the author, "(edited)" and the excerpt, so a screen-reader user hears who and what is quoted, and a
 * deleted quote says "The quoted message was deleted." in its name.
 */
export default function MessageQuote({
  roomId,
  threadNumber,
  quote,
  onShow,
}: {
  roomId: number;
  threadNumber: number;
  quote: MessageQuoteSummary;
  onShow?: (messageId: number) => void;
}) {
  const { t } = useTranslation();
  const { pathname, search } = useLocation();
  const edited = !quote.deleted && quote.editedAt !== null;
  return (
    <UnstyledButton
      component={RouterLink}
      to={messageJumpPath(roomId, threadNumber, quote.messageId, listRouteSearch(pathname, search), parseFocus(new URLSearchParams(search)))}
      onClick={() => onShow?.(quote.messageId)}
      className={`${classes.rule} ${classes.link}`}
    >
      <Group gap={6} wrap="nowrap" align="center">
        <IconQuote size={12} aria-hidden="true" style={{ flex: "none" }} />
        <Text size="xs" fw={700} span className={classes.author} style={{ overflowWrap: "anywhere" }}>
          <VisuallyHidden component="span">{t("threads.quote.lead")} </VisuallyHidden>
          {quote.authorName}
        </Text>
        {edited && (
          <Text size="xs" c="dimmed" span style={{ flex: "none" }}>
            {t("threads.quote.edited")}
          </Text>
        )}
      </Group>
      {quote.deleted ? (
        <Text size="xs" c="dimmed" fs="italic">
          {t("threads.quote.deleted")}
        </Text>
      ) : (
        <Text size="xs" c="dimmed" lineClamp={1}>
          {quoteExcerpt(quote)}
        </Text>
      )}
    </UnstyledButton>
  );
}
