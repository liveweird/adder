import { Anchor } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { tagFilterPath } from "../utils/roomLinks";
import classes from "./ThreadHashtags.module.css";

/**
 * A thread's hashtags as filter links (1.2 A8): each `#tag` leads to the list filtered to that hashtag across open and closed threads
 * (`tagFilterPath`; the value is the folded key the server stores, which is a valid `tag` filter value). Used by the pane header's third row
 * and the side panel's "Hashtags" section, never inside a thread-list row (a row is one link, so its hashtags are text, `ThreadList`). With a
 * `limit` the first `limit` show and the rest become one "+N" button that calls `onShowAll` (the header opens the details, whose section holds
 * them all); a button, not a link, because it goes nowhere by itself.
 */
export default function ThreadHashtags({
  roomId,
  number,
  hashtags,
  limit,
  onShowAll,
  labelled = true,
}: {
  roomId: number;
  number: number;
  hashtags: readonly string[];
  limit?: number;
  onShowAll?: () => void;
  /** A group named "Hashtags" (the default); the side panel's section already is one, so it passes false. */
  labelled?: boolean;
}) {
  const { t } = useTranslation();
  const shown = limit === undefined ? hashtags : hashtags.slice(0, limit);
  const hidden = hashtags.length - shown.length;
  // With a limit the row is the header's: one line, links that shrink to an ellipsis (`ThreadHashtags.module.css`).
  const oneLine = limit !== undefined;
  return (
    <div className={oneLine ? classes.row : classes.wrap} {...(labelled ? { role: "group", "aria-label": t("threads.hashtags.aria") } : {})}>
      {shown.map((tag) => (
        <Anchor
          key={tag}
          component={RouterLink}
          to={tagFilterPath(roomId, number, tag)}
          size="xs"
          underline="always"
          c="dimmed"
          className={classes.tag}
          title={oneLine ? `#${tag}` : undefined}
        >
          {`#${tag}`}
        </Anchor>
      ))}
      {hidden > 0 && onShowAll !== undefined && (
        <Anchor
          component="button"
          type="button"
          size="xs"
          underline="always"
          c="dimmed"
          className={classes.more}
          onClick={onShowAll}
          aria-label={t("threads.hashtags.more", { count: hidden })}
        >
          {t("threads.hashtags.moreShort", { n: hidden })}
        </Anchor>
      )}
    </div>
  );
}
