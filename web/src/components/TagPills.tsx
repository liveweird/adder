import { Anchor, Badge, Group, Text, VisuallyHidden } from "@mantine/core";
import { useTranslation } from "react-i18next";
import classes from "./TagPills.module.css";

/**
 * Curated tags as light neutral pills (gray: a tag is a label, not a state, so it never takes a status colour - and never blue, indigo or
 * violet, which sit beside the brand; Hammurabi's `AdrTagPills`, ported). A retired tag - the registry dropped it - is an outlined,
 * struck-through pill whose accessible name says so, so colour and style are never the only carrier of the difference. `none` is what an
 * empty set reads as (dimmed text); omit it for a table cell that wants nothing. Hashtags are NOT these: they are the `#words` of the
 * messages and have their own display (`ThreadHashtags`).
 *
 * With a `limit` the row is ONE line (a thread-list row, the thread header's tag row): the first `limit` pills show, each shrinking to an
 * ellipsis before the "+N", and the rest are that "+N" - dimmed text in a list row (a row is one link) or, with `onShowAll`, a button that
 * opens the full list (the header opens the details). The "+N" says in words how many tags are hidden.
 */
export default function TagPills({
  tags,
  none,
  limit,
  onShowAll,
}: {
  tags: readonly { name: string; retired: boolean }[];
  none?: string;
  limit?: number;
  onShowAll?: () => void;
}) {
  const { t } = useTranslation();
  if (tags.length === 0) {
    return none ? (
      <Text size="sm" c="dimmed">
        {none}
      </Text>
    ) : null;
  }
  const shown = limit === undefined ? tags : tags.slice(0, limit);
  const hidden = tags.length - shown.length;
  return (
    <Group gap={4} wrap={limit === undefined ? "wrap" : "nowrap"} className={limit === undefined ? undefined : classes.oneLine}>
      {shown.map((tag) => (
        <Badge
          key={tag.name}
          size="sm"
          color="gray"
          variant={tag.retired ? "outline" : "light"}
          className={limit === undefined ? undefined : classes.pill}
          style={{ textTransform: "none", textDecoration: tag.retired ? "line-through" : undefined }}
          title={limit === undefined ? undefined : tag.name}
        >
          <span aria-hidden={tag.retired || undefined}>{tag.name}</span>
          {tag.retired && <VisuallyHidden>{t("tags.retiredAria", { name: tag.name })}</VisuallyHidden>}
        </Badge>
      ))}
      {hidden > 0 &&
        (onShowAll === undefined ? (
          <Text span size="xs" c="dimmed" className={classes.more}>
            <span aria-hidden="true">{t("threads.tags.moreShort", { n: hidden })}</span>
            <VisuallyHidden>{t("threads.tags.more", { count: hidden })}</VisuallyHidden>
          </Text>
        ) : (
          <Anchor
            component="button"
            type="button"
            size="xs"
            underline="always"
            c="dimmed"
            className={classes.more}
            onClick={onShowAll}
            aria-label={t("threads.tags.moreAction", { count: hidden })}
          >
            {t("threads.tags.moreShort", { n: hidden })}
          </Anchor>
        ))}
    </Group>
  );
}
