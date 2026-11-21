import { Badge, Group, Text, VisuallyHidden } from "@mantine/core";
import { useTranslation } from "react-i18next";

/**
 * Curated tags as light neutral pills (gray: a tag is a label, not a state, so it never takes a status colour - and never blue, indigo or
 * violet, which sit beside the brand; Hammurabi's `AdrTagPills`, ported). A retired tag - the registry dropped it - is an outlined,
 * struck-through pill whose accessible name says so, so colour and style are never the only carrier of the difference. `none` is what an
 * empty set reads as (dimmed text); omit it for a table cell that wants nothing. Hashtags are NOT these: they are the `#words` of the
 * messages and have their own display (`ThreadHashtags`).
 */
export default function TagPills({ tags, none }: { tags: readonly { name: string; retired: boolean }[]; none?: string }) {
  const { t } = useTranslation();
  if (tags.length === 0) {
    return none ? (
      <Text size="sm" c="dimmed">
        {none}
      </Text>
    ) : null;
  }
  return (
    <Group gap={4}>
      {tags.map((tag) => (
        <Badge
          key={tag.name}
          size="sm"
          color="gray"
          variant={tag.retired ? "outline" : "light"}
          style={{ textTransform: "none", textDecoration: tag.retired ? "line-through" : undefined }}
        >
          <span aria-hidden={tag.retired || undefined}>{tag.name}</span>
          {tag.retired && <VisuallyHidden>{t("tags.retiredAria", { name: tag.name })}</VisuallyHidden>}
        </Badge>
      ))}
    </Group>
  );
}
