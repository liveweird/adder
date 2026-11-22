import { useState } from "react";
import { useMatch, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ActionIcon, Kbd, Text, UnstyledButton } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { useOs } from "@mantine/hooks";
import { Spotlight, type SpotlightActionData, type SpotlightActionGroupData, type SpotlightFilterFunction } from "@mantine/spotlight";
import { IconActivity, IconEye, IconHash, IconSearch } from "@tabler/icons-react";
import { useAdmin } from "../auth";
import { getRoom } from "../api/rooms";
import { ACCOUNT_NAV, visibleSections } from "../utils/navigation";
import { useRailRooms } from "../hooks/useRailRooms";
import { setTelemetryPanelOpen, useTelemetryPanelOpen } from "../hooks/useTelemetryPanel";
import { parseRoomId, roomPath, roomSearchPath, searchPath } from "../utils/roomLinks";
import { roomKeys } from "../utils/roomKeys";
import { queryProblem, searchResultsPath, watchedThreadsPath } from "../utils/searchParams";
import { palette, paletteStore } from "../utils/commandPalette";
import { foldDiacritics } from "../utils/text";
import classes from "../theme.module.css";

/**
 * The command palette: ⌘K / Ctrl K, or the search-looking trigger in the header. Pages — every page the
 * session may see (the same nav model as the sidebar plus the account leaves) — and Rooms, the caller's active
 * rooms from the rail's own query; from a searchable term on, "Search all rooms for ..." (and, inside a room, "Search {room} for ...") links to the
 * search pages; an ADMIN also gets Actions. Matched client-side by folded label. Renders BOTH the trigger and the Spotlight, mounted
 * once in the shell header. No `highlightQuery`: it splits a label into `<mark>` + text
 * fragments, and tests/e2e locate results by their full name.
 */
export default function CommandPalette({ shortcutEnabled = true }: { shortcutEnabled?: boolean }) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const navigate = useNavigate();
  const os = useOs();
  const [query, setQuery] = useState("");
  const telemetryOpen = useTelemetryPanelOpen();
  // The rail's query (same key, so one request): the caller's active rooms.
  const roomItems = useRailRooms(false).active.data?.items ?? [];
  // The room the caller is in, if any (the same read as the room's own pages, so one request): "Search {room} for ..." needs its name.
  const openRoomId = parseRoomId(useMatch("/rooms/:id/*")?.params.id);
  const openRoomRead = useQuery({ queryKey: roomKeys.detail(openRoomId), queryFn: () => getRoom(openRoomId!), enabled: openRoomId !== null });
  // A failed read (a 404: the room is gone, or the caller left it) takes the action away, even over a record cached from before.
  const openRoom = openRoomRead.isError ? undefined : openRoomRead.data;

  const go = (to: string) => {
    palette.close();
    navigate(to);
  };

  const pages: SpotlightActionGroupData = {
    group: t("appShell.palette.groupPages"),
    actions: [
      ...[...visibleSections(admin).flatMap((section) => section.items), ...ACCOUNT_NAV].map((leaf) => {
        const Icon = leaf.icon;
        return {
          id: `page:${leaf.to}`,
          label: t(leaf.label),
          keywords: leaf.keywords ? [...leaf.keywords] : undefined,
          leftSection: <Icon size={18} stroke={1.5} />,
          onClick: () => go(leaf.to),
        };
      }),
      // The search across rooms with the "Threads I watch" switch on and no term (any caller; a caller with no rooms gets an empty list).
      {
        id: "page:watching",
        label: t("appShell.palette.watching"),
        leftSection: <IconEye size={18} stroke={1.5} />,
        onClick: () => go(watchedThreadsPath(searchPath)),
      },
    ],
  };
  const roomGroup: SpotlightActionGroupData = {
    group: t("appShell.palette.groupRooms"),
    actions: roomItems.map((room) => ({
      id: `room:${room.id}`,
      label: room.name,
      leftSection: <IconHash size={18} stroke={1.5} />,
      onClick: () => go(roomPath(room.id)),
    })),
  };
  // Search: offered once the typed words can be searched (the server's rules, `queryProblem`); the palette itself matches by label, so this group
  // passes the filter untouched, and a pick opens the search page with the term in its URL (a search is submitted, never run from here).
  const term = query.trim();
  const searchActions: SpotlightActionData[] = [];
  if (openRoomId !== null && openRoom) {
    searchActions.push({
      id: "search:room",
      label: t("appShell.palette.searchRoom", { room: openRoom.name, q: term }),
      leftSection: <IconSearch size={18} stroke={1.5} />,
      onClick: () => go(searchResultsPath(roomSearchPath(openRoomId), term)),
    });
  }
  searchActions.push({
    id: "search:all",
    label: t("appShell.palette.searchAll", { q: term }),
    leftSection: <IconSearch size={18} stroke={1.5} />,
    onClick: () => go(searchResultsPath(searchPath, term)),
  });
  const searchGroup: SpotlightActionGroupData = { group: t("appShell.palette.groupSearch"), actions: searchActions };
  // Actions: the ADMIN-only toggle of the telemetry panel (the same flag as the account menu's item).
  const actions: SpotlightActionGroupData = {
    group: t("appShell.palette.groupActions"),
    actions: [
      {
        id: "action:telemetry-panel",
        label: t(telemetryOpen ? "telemetry.palette.hide" : "telemetry.palette.show"),
        keywords: t("telemetry.palette.keywords").split(" "),
        leftSection: <IconActivity size={18} stroke={1.5} />,
        onClick: () => {
          palette.close();
          setTelemetryPanelOpen(!telemetryOpen);
        },
      },
    ],
  };
  // Pages match the folded query on their label or one of their extra keywords.
  const filter: SpotlightFilterFunction = (q, actions) => {
    const folded = foldDiacritics(q.trim());
    return actions.flatMap((entry): (SpotlightActionData | SpotlightActionGroupData)[] => {
      if (!("actions" in entry)) return [entry];
      const group = entry as SpotlightActionGroupData;
      if (group.group === searchGroup.group) return [group];
      const items = group.actions.filter((a: SpotlightActionData) =>
        [String(a.label ?? ""), ...[a.keywords ?? []].flat()].some((term) => foldDiacritics(term).includes(folded)),
      );
      return items.length > 0 ? [{ ...group, actions: items }] : [];
    });
  };
  const shortcut = os === "macos" ? "⌘ K" : "Ctrl K";
  return (
    <>
      {/* No aria-label on the wide trigger: its name is its visible words (WCAG 2.5.3 Label in Name); only the icon-only one needs a label. */}
      <UnstyledButton className={classes.paletteTrigger} visibleFrom="sm" onClick={palette.open}>
        <IconSearch size={16} />
        <Text component="span" size="sm" inherit>
          {t("appShell.palette.placeholder")}
        </Text>
        <Kbd size="xs">{shortcut}</Kbd>
      </UnstyledButton>
      <ActionIcon hiddenFrom="sm" size="lg" aria-label={t("appShell.palette.open")} onClick={palette.open}>
        <IconSearch size={18} />
      </ActionIcon>
      <Spotlight
        store={paletteStore}
        shortcut={shortcutEnabled ? "mod + K" : null}
        query={query}
        onQueryChange={setQuery}
        actions={[pages, ...(roomItems.length > 0 ? [roomGroup] : []), ...(queryProblem(term) === null ? [searchGroup] : []), ...(admin ? [actions] : [])]}
        filter={filter}
        nothingFound={t("appShell.palette.nothingFound")}
        scrollable
        maxHeight={420}
        searchProps={{
          leftSection: <IconSearch size={18} stroke={1.5} />,
          placeholder: t("appShell.palette.placeholder"),
        }}
      />
    </>
  );
}
