import { Badge } from "@mantine/core";
import { IconEye, IconLock, IconWorld } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { RoomAccessType } from "../api/rooms";

const ICONS = {
  PUBLIC: IconWorld,
  RESTRICTED_VISIBLE: IconEye,
  RESTRICTED_HIDDEN: IconLock,
} as const;

/** A text-and-icon label for the room's immutable discovery rule. */
export default function RoomAccessBadge({ accessType, size = "sm" }: { accessType: RoomAccessType; size?: "xs" | "sm" }) {
  const { t } = useTranslation();
  const Icon = ICONS[accessType];
  return (
    <Badge size={size} variant="light" color="gray" leftSection={<Icon size={12} aria-hidden="true" />}>
      {t(`rooms.access.${accessType}.label`)}
    </Badge>
  );
}
