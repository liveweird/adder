import { Box, Divider, NavLink, ScrollArea, Text, Tooltip } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { useRailRooms } from "../hooks/useRailRooms";
import { ROOMS_SECTION, type NavSection } from "../utils/navigation";
import classes from "../theme.module.css";
import RoomsRail from "./RoomsRail";
import VersionStamp from "./VersionStamp";

export default function WorkspaceNav({
  sections,
  rail = false,
  activeTo,
  railRooms,
  mentions,
  showArchived,
  onShowArchivedChange,
  onNavigate,
}: {
  sections: readonly NavSection[];
  rail?: boolean;
  activeTo: string | null;
  railRooms: ReturnType<typeof useRailRooms>;
  mentions: ReadonlyMap<number, number>;
  showArchived: boolean;
  onShowArchivedChange: (next: boolean) => void;
  onNavigate?: (to: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <>
      <ScrollArea component="nav" aria-label={t("appShell.navigation")} type="auto" scrollbars="y" scrollbarSize={6} className={classes.navScroll}>
        {sections.map((section, index) => (
          <Box key={section.label} role="group" aria-label={t(section.label)} className={classes.navSection}>
            {rail ? (
              index > 0 && <Divider my={6} mx={8} />
            ) : (
              <Text component="div" className={classes.navSectionLabel}>
                {t(section.label)}
              </Text>
            )}
            {section.items.map(({ to, icon: Icon, label: labelKey }) => {
              const active = to === activeTo;
              const label = t(labelKey);
              const link = (
                <NavLink
                  key={to}
                  component={RouterLink}
                  to={to}
                  active={active}
                  aria-current={active ? "page" : undefined}
                  aria-label={rail ? label : undefined}
                  label={rail ? undefined : label}
                  leftSection={<Icon size={18} stroke={1.65} aria-hidden="true" />}
                  className={rail ? classes.railLink : undefined}
                  onClick={onNavigate ? () => onNavigate(to) : undefined}
                />
              );
              return rail ? <Tooltip key={to} label={label} position="right" withinPortal>{link}</Tooltip> : link;
            })}
            {section.label === ROOMS_SECTION && (
              <RoomsRail
                rooms={railRooms}
                showArchived={showArchived}
                onShowArchivedChange={onShowArchivedChange}
                collapsed={rail}
                activeTo={activeTo}
                onNavigate={onNavigate}
                mentions={mentions}
              />
            )}
          </Box>
        ))}
      </ScrollArea>
      <div className={classes.navFooter}>
        <VersionStamp to="/changelog" ta={rail ? "center" : "start"} compact={rail} onNavigate={onNavigate} />
      </div>
    </>
  );
}
