import { Button, Group } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { roomPath, roomSettingsPath } from "../utils/roomLinks";

export type RoomTabKey = "home" | "settings";

/**
 * The room's page switcher: real links (a route per tab), the open one marked with `aria-current`. Only the pages that exist are
 * listed: the room's members and workflow tabs join when those pages do. Not an ARIA tablist — there is no panel to switch, the
 * route is the state.
 */
export default function RoomTabs({ id, active }: { id: number; active: RoomTabKey }) {
  const { t } = useTranslation();
  const tabs: ReadonlyArray<{ key: RoomTabKey; to: string; label: string }> = [
    { key: "home", to: roomPath(id), label: t("rooms.tabs.home") },
    { key: "settings", to: roomSettingsPath(id), label: t("rooms.tabs.settings") },
  ];
  return (
    <Group component="nav" aria-label={t("rooms.tabs.aria")} gap="xs">
      {tabs.map((tab) => (
        <Button
          key={tab.key}
          component={RouterLink}
          to={tab.to}
          size="compact-md"
          variant={tab.key === active ? "light" : "subtle"}
          color={tab.key === active ? undefined : "gray"}
          aria-current={tab.key === active ? "page" : undefined}
        >
          {tab.label}
        </Button>
      ))}
    </Group>
  );
}
