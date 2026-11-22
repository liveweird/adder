import { useTranslation } from "react-i18next";
import { ActionIcon, Group, Stack, Text } from "@mantine/core";
import { IconQuote, IconX } from "@tabler/icons-react";
import type { QuoteDraft } from "../hooks/useThreadDrafts";
import { quoteDraftExcerpt } from "../utils/quoteDraft";
import classes from "./MessageQuote.module.css";

/**
 * The line above the reply box that says what the reply will quote (1.3): the quote icon, "Quoting {author}", one clamped line of the picked
 * message's excerpt (or, once the message is known to be gone, the "no longer available" words and no text: 1.3 A7, `utils/quoteDraft.ts`) and a "Remove the quote" button. Plain text,
 * never Markdown or HTML. The composer owns the polite status line that says it (the text here is `aria-hidden`, so it is not read twice) and puts the focus back in the box after
 * the removal (the button goes with the line).
 */
export default function ComposerQuote({ quote, onRemove }: { quote: QuoteDraft; onRemove: () => void }) {
  const { t } = useTranslation();
  return (
    <Group gap="xs" wrap="nowrap" align="flex-start" className={classes.rule}>
      <IconQuote size={14} aria-hidden="true" style={{ flex: "none", marginTop: 2 }} />
      {/* Hidden from assistive technology: the composer's status line says the same words once, when the quote is set. */}
      <Stack gap={0} miw={0} style={{ flex: 1 }} aria-hidden="true">
        <Text size="xs" fw={600} style={{ overflowWrap: "anywhere" }}>
          {t("threads.quote.chip", { author: quote.authorName })}
        </Text>
        <Text size="xs" c="dimmed" lineClamp={1} style={{ overflowWrap: "anywhere" }}>
          {quoteDraftExcerpt(quote, t)}
        </Text>
      </Stack>
      <ActionIcon size="sm" aria-label={t("threads.quote.remove")} onClick={onRemove}>
        <IconX size={14} />
      </ActionIcon>
    </Group>
  );
}
