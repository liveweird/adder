import { useTranslation } from "react-i18next";
import { Badge } from "@mantine/core";
import { IconPinFilled } from "@tabler/icons-react";

/**
 * The mark of a pinned THREAD (1.2 D5): a "Pinned" badge with a filled pin, beside the status pill of the thread's list row and of its pane
 * header. Pinned is a neutral state, so the badge is the theme's default gray (`web/CLAUDE.md` colour vocabulary; the olive brand is the
 * interactive accent only, and blue is the unread cue); the pin shape and the word carry the meaning, never the hue. In a list row the whole row
 * is one link whose name must stay the thread's, and the row already says "Pinned" in its description (`threads.pin.mark`), so there the badge
 * is `hidden` from assistive technology; in the header it is read.
 */
export default function PinMark({ size = "md", hidden = false }: { size?: "sm" | "md"; hidden?: boolean }) {
  const { t } = useTranslation();
  return (
    <Badge size={size} leftSection={<IconPinFilled size={size === "sm" ? 10 : 12} aria-hidden="true" />} aria-hidden={hidden || undefined} style={{ minWidth: "max-content" }} data-pin-mark="thread">
      {t("threads.pin.mark")}
    </Badge>
  );
}
