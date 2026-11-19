import { useEffect, useState } from "react";
import { Badge, Tooltip, VisuallyHidden } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { LiveStatus as Status } from "../utils/liveClient";

/** A reconnect shorter than this is not worth a badge: a token expiry or a server restart reads as nothing. */
const DOWN_BADGE_DELAY_MS = 5_000;

/**
 * The live channel's indicator in the shell header. The `role="status"` region is ALWAYS mounted (a live region announces only what changes
 * inside one that already exists); it shows nothing while the channel is live, "Reconnecting…" once it has been down for five seconds, and
 * "Live updates off" at once when this tab was refused for having too many siblings.
 */
export default function LiveStatus({ status }: { status: Status }) {
  const { t } = useTranslation();
  const [downLongEnough, setDownLongEnough] = useState(false);
  useEffect(() => {
    if (status !== "down") return;
    const timer = setTimeout(() => setDownLongEnough(true), DOWN_BADGE_DELAY_MS);
    return () => {
      clearTimeout(timer);
      setDownLongEnough(false);
    };
  }, [status]);

  const kind = status === "paused" ? "paused" : status === "down" && downLongEnough ? "reconnecting" : null;
  return (
    // Empty, the region leaves the layout (absolute) so it adds no gap to the header's `Group`, yet stays in the accessibility tree for announcements.
    <div role="status" style={kind ? undefined : { position: "absolute" }}>
      {kind && (
        <Tooltip label={t(`appShell.live.${kind}Hint`)} withinPortal>
          <Badge color="orange" variant="light" tabIndex={0}>
            {t(`appShell.live.${kind}`)}
          </Badge>
        </Tooltip>
      )}
      {kind && <VisuallyHidden>{t(`appShell.live.${kind}Hint`)}</VisuallyHidden>}
    </div>
  );
}
