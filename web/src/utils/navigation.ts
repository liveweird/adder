import type { ParseKeys } from "i18next";
import { IconDoor, IconBellCog, IconHistory, IconHome2, IconKey, IconListCheck, IconSearch, IconTags, IconToggleLeft, IconUsers, IconUsersGroup, type Icon } from "@tabler/icons-react";
import { roomsPath, searchPath } from "./roomLinks";
import { tagsPath } from "./tagLinks";
import { teamsPath } from "./teamLinks";
import { usersPath } from "./userLinks";
import { workflowTemplatesPath } from "./workflowTemplateLinks";

export type NavLeaf = {
  to: string;
  /** An i18n key, resolved with t() at render time. */
  label: ParseKeys;
  icon: Icon;
  /** When set, the leaf renders only for ADMIN sessions. */
  adminOnly?: boolean;
  /**
   * Further routes this leaf stays highlighted on (a report group's other tabs, each of which
   * is its own route). Prefix-matched like `to`; the leaf itself always links to `to`.
   */
  activeFor?: ReadonlyArray<string>;
  /** Extra palette search terms (raw, language-neutral) — a page renamed since people learned its old name. */
  keywords?: ReadonlyArray<string>;
};

/** A labelled, always-open block of leaves — a section, never a collapsible group. */
export type NavSection = {
  label: ParseKeys;
  items: ReadonlyArray<NavLeaf>;
};

/** The Home route — everyone lands here after sign-in. */
export const homePath = "/";

/**
 * The navigation model, shared by the sidebar and the command palette. Sections are labelled,
 * always-open blocks (never collapsible groups): every leaf is always in the DOM, so tests and
 * deep links address the links directly, and a static label costs less vertical space than a
 * toggle. Home is visible to everyone; the Rooms section links to the directory above the caller's rooms
 * (those dynamic entries join `activeNavPath`); the Administration section holds Teams (everyone reads
 * the flat teams list; only its create/edit/delete are ADMIN) alongside the ADMIN-only Users, Workflow
 * templates, Tags and Feature flags leaves — a non-admin session sees just Teams there.
 */
/** The section the shell renders the rooms rail in. */
export const ROOMS_SECTION: ParseKeys = "appShell.section.rooms";

const NAV_SECTIONS: ReadonlyArray<NavSection> = [
  {
    label: "appShell.section.overview",
    items: [
      { to: homePath, label: "appShell.nav.home", icon: IconHome2 },
      { to: searchPath, label: "appShell.nav.search", icon: IconSearch },
    ],
  },
  {
    label: "appShell.section.rooms",
    items: [
      { to: roomsPath, label: "appShell.nav.allRooms", icon: IconDoor },
    ],
  },
  {
    label: "appShell.section.administration",
    items: [
      { to: teamsPath, label: "appShell.nav.teams", icon: IconUsersGroup },
      { to: usersPath, label: "appShell.nav.users", icon: IconUsers, adminOnly: true },
      { to: workflowTemplatesPath, label: "appShell.nav.workflowTemplates", icon: IconListCheck, adminOnly: true },
      { to: tagsPath, label: "appShell.nav.tags", icon: IconTags, adminOnly: true },
      { to: "/feature-flags", label: "appShell.nav.featureFlags", icon: IconToggleLeft, adminOnly: true },
    ],
  },
];

/** Account-scoped leaves: the header user menu and the palette render them, the sidebar never. */
export const ACCOUNT_NAV: ReadonlyArray<NavLeaf> = [
  { to: "/change-password", label: "appShell.nav.changePassword", icon: IconKey },
  { to: "/notification-preferences", label: "appShell.nav.notificationPreferences", icon: IconBellCog, keywords: ["notification emails", "powiadomienia e-mailem"] },
  { to: "/changelog", label: "appShell.nav.changelog", icon: IconHistory },
];

/** The sections a session may see: admin-only leaves filtered, empty sections dropped. */
export function visibleSections(admin: boolean): NavSection[] {
  return NAV_SECTIONS.flatMap((section) => {
    const items = section.items.filter((leaf) => !leaf.adminOnly || admin);
    return items.length > 0 ? [{ ...section, items }] : [];
  });
}

/**
 * Longest-matching-prefix active-link resolution — "/" only matches exactly. A leaf matches on its
 * own `to` and on every `activeFor` route; the answer is always the leaf's `to`. Only the routing part of a leaf is read, so
 * the shell's dynamic entries (a room of the rail) join the same resolution: `/rooms/12/members` lights room 12, not "All rooms".
 */
export function activeNavPath(pathname: string, leaves: ReadonlyArray<Pick<NavLeaf, "to" | "activeFor">>): string | null {
  const matches = (path: string) =>
    path === "/" ? pathname === "/" : pathname === path || pathname.startsWith(`${path}/`);
  return (
    leaves
      .flatMap((leaf) => [leaf.to, ...(leaf.activeFor ?? [])].filter(matches).map((path) => ({ to: leaf.to, length: path.length })))
      .sort((a, b) => b.length - a.length)[0]?.to ?? null
  );
}
