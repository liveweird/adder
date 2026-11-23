import { describe, expect, test } from "vitest";
import { ACCOUNT_NAV, activeNavPath, homePath, ROOMS_SECTION, visibleSections } from "./navigation";

describe("visibleSections", () => {
  test("a regular session sees Overview, Rooms and Administration — Teams stays, the admin-only leaves drop", () => {
    const sections = visibleSections(false);
    expect(sections.map((s) => s.label)).toEqual(["appShell.section.overview", ROOMS_SECTION, "appShell.section.administration"]);
    const paths = sections.flatMap((s) => s.items.map((l) => l.to));
    expect(paths).toEqual(["/", "/search", "/rooms", "/rooms/organize", "/teams"]);
  });

  test("an admin session gets Teams, Users, Workflow templates, Tags and Feature flags in the Administration section", () => {
    const admin = visibleSections(true).find((s) => s.label === "appShell.section.administration");
    expect(admin?.items.map((l) => l.to)).toEqual(["/teams", "/users", "/workflow-templates", "/tags", "/feature-flags"]);
  });

  test("the account leaves never sit in a section", () => {
    const sectionPaths = visibleSections(true).flatMap((s) => s.items.map((l) => l.to));
    for (const leaf of ACCOUNT_NAV) expect(sectionPaths).not.toContain(leaf.to);
    expect(ACCOUNT_NAV.map((l) => l.to)).toEqual(["/change-password", "/notification-preferences", "/changelog"]);
  });
});

describe("activeNavPath", () => {
  const leaves = visibleSections(true).flatMap((s) => s.items);

  test("resolves the longest matching prefix", () => {
    expect(activeNavPath("/users/new", leaves)).toBe("/users");
    expect(activeNavPath("/users/3/edit", leaves)).toBe("/users");
    expect(activeNavPath("/feature-flags", leaves)).toBe("/feature-flags");
    expect(activeNavPath("/teams/3", leaves)).toBe("/teams");
    expect(activeNavPath("/workflow-templates/new", leaves)).toBe("/workflow-templates");
    expect(activeNavPath("/workflow-templates/3/edit", leaves)).toBe("/workflow-templates");
    expect(activeNavPath("/tags", leaves)).toBe("/tags");
    expect(activeNavPath("/rooms", leaves)).toBe("/rooms");
    expect(activeNavPath("/rooms/new", leaves)).toBe("/rooms");
    expect(activeNavPath("/rooms/organize", leaves)).toBe("/rooms/organize");
    expect(activeNavPath("/search", leaves)).toBe("/search");
  });

  test("a room of the rail lights up on every page of that room, and the static leaf on the rest", () => {
    // Only the routing part of a leaf is read, so the shell's dynamic entries join the same resolution.
    const withRooms = [...leaves, { to: "/rooms/12" }, { to: "/rooms/120" }];
    expect(activeNavPath("/rooms/12", withRooms)).toBe("/rooms/12");
    expect(activeNavPath("/rooms/12/members", withRooms)).toBe("/rooms/12");
    expect(activeNavPath("/rooms/12/settings", withRooms)).toBe("/rooms/12");
    expect(activeNavPath("/rooms/120/workflow", withRooms)).toBe("/rooms/120");
    expect(activeNavPath("/rooms/13", withRooms)).toBe("/rooms");
    expect(activeNavPath("/rooms", withRooms)).toBe("/rooms");
  });

  test("a leaf stays highlighted on every route it lists in activeFor, and still links to its own", () => {
    const custom = [
      { to: "/a", label: "appShell.nav.home", icon: leaves[0].icon, activeFor: ["/b/deep"] },
      { to: "/b", label: "appShell.nav.home", icon: leaves[0].icon },
    ] as const;
    // The longest match still wins across leaves.
    expect(activeNavPath("/b/deep/x", custom)).toBe("/a");
    expect(activeNavPath("/b/other", custom)).toBe("/b");
  });

  test("the root matches only exactly", () => {
    expect(activeNavPath(homePath, leaves)).toBe("/");
    expect(activeNavPath("/nowhere", leaves)).toBeNull();
  });
});
