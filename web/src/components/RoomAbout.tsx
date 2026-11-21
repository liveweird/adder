import { useTranslation } from "react-i18next";
import { ActionIcon, Popover, Text, Tooltip } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";

/**
 * "About this room" in the compact room header (1.2 A13): the room's description behind an info button instead of a line of its own. It
 * renders only when the room has a description; the text is shown as plain text, never HTML. Opening it moves focus onto the text (`trapFocus`:
 * Mantine closes a popover on Escape only from inside its dropdown), so Escape closes it and returns focus to the button.
 */
export default function RoomAbout({ description }: { description?: string | null }) {
  const { t } = useTranslation();
  if (!description) return null;
  return (
    <Popover width={320} position="bottom-end" shadow="md" withArrow trapFocus>
      <Tooltip label={t("rooms.about.button")}>
        <Popover.Target>
          <ActionIcon variant="default" aria-label={t("rooms.about.button")}>
            <IconInfoCircle size={16} />
          </ActionIcon>
        </Popover.Target>
      </Tooltip>
      <Popover.Dropdown>
        <Text size="sm" tabIndex={-1} data-autofocus style={{ overflowWrap: "anywhere" }}>
          {description}
        </Text>
      </Popover.Dropdown>
    </Popover>
  );
}
