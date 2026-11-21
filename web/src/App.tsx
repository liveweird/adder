import { lazy, Suspense, useMemo } from "react";
import { ActionIcon, AppShell, Box, Burger, Divider, Group, NavLink, ScrollArea, Text, Tooltip } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand } from "@tabler/icons-react";
import { Link as RouterLink, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { RedirectIfAuthed, RequireAdmin, RequireAuth, useAdmin } from "./auth";
import BrandLogo from "./components/BrandLogo";
import CommandPalette from "./components/CommandPalette";
import LiveStatus from "./components/LiveStatus";
import LoadingBlock from "./components/LoadingBlock";
import NotificationsButton from "./components/NotificationsButton";
import RoomsRail from "./components/RoomsRail";
import ThreadDraftsProvider from "./components/ThreadDraftsProvider";
import TelemetryPanelCrash from "./components/TelemetryPanelCrash";
import UserMenu from "./components/UserMenu";
import VersionStamp from "./components/VersionStamp";
import ErrorBoundary, { RouteErrorBoundary } from "./components/ErrorBoundary";
import { KnownNamesContext, useSessionKnownNames } from "./utils/knownNames";
import { activeNavPath, ROOMS_SECTION, visibleSections, type NavLeaf } from "./utils/navigation";
import { roomPath } from "./utils/roomLinks";
import { unseenByRoom } from "./utils/unread";
import { usePrintLifecycle } from "./hooks/usePrintLifecycle";
import { useLiveUpdates } from "./hooks/useLiveUpdates";
import { useRailRooms } from "./hooks/useRailRooms";
import { useAddressedNotifications } from "./hooks/useUnseenNotifications";
import { isBoolean, useStoredState } from "./hooks/useStoredState";
import { setTelemetryPanelOpen, useTelemetryPanelOpen } from "./hooks/useTelemetryPanel";
import classes from "./theme.module.css";

// The ADMIN telemetry panel: a lazy chunk a non-admin never downloads (it is only mounted for an ADMIN with the panel open).
const TelemetryPanel = lazy(() => import("./components/TelemetryPanel"));
const Login = lazy(() => import("./pages/Login"));
const Home = lazy(() => import("./pages/Home"));
const Search = lazy(() => import("./pages/Search"));
const RoomSearch = lazy(() => import("./pages/RoomSearch"));
const Rooms = lazy(() => import("./pages/Rooms"));
const CreateRoom = lazy(() => import("./pages/CreateRoom"));
const RoomHome = lazy(() => import("./pages/RoomHome"));
const RoomMembers = lazy(() => import("./pages/RoomMembers"));
const RoomSettings = lazy(() => import("./pages/RoomSettings"));
const RoomWorkflow = lazy(() => import("./pages/RoomWorkflow"));
const Person = lazy(() => import("./pages/Person"));
const Teams = lazy(() => import("./pages/Teams"));
const TeamDetails = lazy(() => import("./pages/TeamDetails"));
const ResetPassword = lazy(() => import("./pages/ResetPassword"));
const ConfirmPasswordReset = lazy(() => import("./pages/ConfirmPasswordReset"));
const Users = lazy(() => import("./pages/Users"));
const UserFeatures = lazy(() => import("./pages/UserFeatures"));
const FeatureFlags = lazy(() => import("./pages/FeatureFlags"));
const WorkflowTemplates = lazy(() => import("./pages/WorkflowTemplates"));
const CreateWorkflowTemplate = lazy(() => import("./pages/CreateWorkflowTemplate"));
const EditWorkflowTemplate = lazy(() => import("./pages/EditWorkflowTemplate"));
const CreateUser = lazy(() => import("./pages/CreateUser"));
const EditUser = lazy(() => import("./pages/EditUser"));
const ChangePassword = lazy(() => import("./pages/ChangePassword"));
const Changelog = lazy(() => import("./pages/Changelog"));
const NotificationPreferences = lazy(() => import("./pages/NotificationPreferences"));
const NotFound = lazy(() => import("./pages/NotFound"));

function RouteFallback() {
  return (
    <LoadingBlock mih={200} />
  );
}

function Shell() {
  const { t } = useTranslation();
  const admin = useAdmin();
  // The names this session has seen, above the routes and inside the session boundary (a new sign-in, here or in another tab, starts with none).
  const knownNames = useSessionKnownNames();
  const [opened, { toggle, close }] = useDisclosure();
  const { pathname } = useLocation();
  // Every page prints in the light scheme (the print-aware provider in main.tsx reads what this reports).
  usePrintLifecycle();
  // Desktop navbar collapse — device-level, persisted like the other view settings; the
  // mobile overlay keeps its own `opened` disclosure above and always shows full labels.
  const [navCollapsed, setNavCollapsed] = useStoredState("appShell.navCollapsed", false, isBoolean);
  const rail = navCollapsed && !opened;
  // The telemetry panel: the stored flag is device-level, but a stored `true` shows nothing to a non-admin.
  const panelStored = useTelemetryPanelOpen();
  const showPanel = admin && panelStored;
  const closePanel = () => setTelemetryPanelOpen(false);

  // The nav model lives in utils/navigation.ts (shared with the command palette): sections
  // of always-present leaves, admin-only ones filtered per session (the routes are guarded
  // too), an empty section disappearing with them.
  const sections = visibleSections(admin);
  // The caller's rooms (the rail under "All rooms"): the toggle for the archived ones is device-level, like the other view settings.
  const [showArchived, setShowArchived] = useStoredState("rooms.rail.showArchived", false, isBoolean);
  const railRooms = useRailRooms(showArchived);
  // The unseen notices ADDRESSED to the caller (a mention of any kind, a hand-over; the bell's own query, shared): their count per room is
  // the `@` number in the rail. A reply or a new thread is in the bell and its badge, never here (0.6 D1).
  const addressed = useAddressedNotifications();
  const mentions = useMemo(() => unseenByRoom(addressed.items), [addressed.items]);
  // The tab's one live socket: it follows the ACTIVE rail rooms and the open room (the archived rail and "All rooms" refresh on focus).
  const liveStatus = useLiveUpdates((railRooms.active.data?.items ?? []).map((room) => room.id));
  // The rooms join the leaves in ONE longest-prefix resolution, so /rooms/12/members lights room 12, not "All rooms".
  const roomLeaves = [...(railRooms.active.data?.items ?? []), ...(showArchived ? (railRooms.archived.data?.items ?? []) : [])].map((room) => ({
    to: roomPath(room.id),
  }));
  const activeTo = activeNavPath(pathname, [...sections.flatMap((section) => section.items), ...roomLeaves]);

  const renderLeaf = (leaf: NavLeaf) => {
    const active = leaf.to === activeTo;
    const Icon = leaf.icon;
    const label = t(leaf.label);
    if (rail) {
      // Icon-only: the accessible NAME stays the label (aria-label), so every locator that
      // finds the link by name works in both modes.
      return (
        <Tooltip key={leaf.to} label={label} position="right" withinPortal>
          <NavLink
            component={RouterLink}
            to={leaf.to}
            active={active}
            aria-current={active ? "page" : undefined}
            aria-label={label}
            leftSection={<Icon size={20} stroke={1.5} />}
            className={classes.railLink}
            onClick={close}
          />
        </Tooltip>
      );
    }
    return (
      <NavLink
        key={leaf.to}
        component={RouterLink}
        to={leaf.to}
        active={active}
        aria-current={active ? "page" : undefined}
        label={label}
        leftSection={<Icon size={18} stroke={1.5} />}
        onClick={close}
      />
    );
  };

  return (
    <AppShell
      header={{ height: 48 }}
      navbar={{ width: { base: 240, sm: rail ? 64 : 240 }, breakpoint: "sm", collapsed: { mobile: !opened } }}
      // 420 px beside the page from `lg` up (the content gives way: at 1200 px the 240 px navbar and the panel still leave ~540 px);
      // below it the panel covers the page edge to edge, since a pushed 420 px next to the navbar would leave a sliver.
      aside={{ width: 420, breakpoint: "lg", collapsed: { desktop: !showPanel, mobile: !showPanel } }}
      padding="md"
    >
      <AppShell.Header>
        <a href="#main-content" className="skip-link">
          {t("appShell.skipToContent")}
        </a>
        <Group h={48} px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger
              opened={opened}
              onClick={toggle}
              hiddenFrom="sm"
              size="sm"
              aria-label={t("appShell.toggleMobileNav")}
              aria-expanded={opened}
            />
            {/* Sits beside the Burger, where the nav it toggles lives. */}
            <Tooltip label={t("appShell.toggleNav")} position="right" withinPortal>
              <ActionIcon
                variant="subtle"
                visibleFrom="sm"
                onClick={() => setNavCollapsed(!navCollapsed)}
                aria-label={t("appShell.toggleNav")}
                data-expanded={!navCollapsed || undefined}
              >
                {navCollapsed ? (
                  <IconLayoutSidebarLeftExpand size={18} />
                ) : (
                  <IconLayoutSidebarLeftCollapse size={18} />
                )}
              </ActionIcon>
            </Tooltip>
            <BrandLogo />
            <Text fw={600} size="md" className={classes.brandText}>
              {t("appShell.brand")}
            </Text>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <LiveStatus status={liveStatus} />
            <NotificationsButton />
            <CommandPalette />
            <UserMenu />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        {/* The link list scrolls when it outgrows the viewport; the version stamp stays pinned. */}
        <AppShell.Section grow component={ScrollArea} type="hover" scrollbarSize={6} offsetScrollbars>
          {sections.map((section, index) => (
            // A labelled, always-open block — never a toggle, so every leaf stays in the DOM
            // for tests and deep links.
            <Box key={section.label} role="group" aria-label={t(section.label)}>
              {rail ? (
                index > 0 && <Divider my={4} mx={8} />
              ) : (
                <Text component="div" className={classes.navSectionLabel}>
                  {t(section.label)}
                </Text>
              )}
              {section.items.map(renderLeaf)}
              {section.label === ROOMS_SECTION && (
                <RoomsRail
                  rooms={railRooms}
                  showArchived={showArchived}
                  onShowArchivedChange={setShowArchived}
                  collapsed={rail}
                  activeTo={activeTo}
                  onNavigate={close}
                  mentions={mentions}
                />
              )}
            </Box>
          ))}
        </AppShell.Section>
        <AppShell.Section pt="xs" style={{ borderTop: "1px solid var(--mantine-color-default-border)" }}>
          <VersionStamp to="/changelog" ta="center" pt={4} compact={rail} />
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main id="main-content" tabIndex={-1}>
        {/* A page crash stays inside the main area — header/nav keep working, and navigating
            anywhere remounts the boundary (see components/ErrorBoundary.tsx). The inner
            Suspense keeps the shell mounted while a lazy page chunk loads. */}
        <KnownNamesContext.Provider value={knownNames}>
          <ThreadDraftsProvider>
            <RouteErrorBoundary>
              <Suspense fallback={<RouteFallback />}>
                <Outlet />
              </Suspense>
            </RouteErrorBoundary>
          </ThreadDraftsProvider>
        </KnownNamesContext.Provider>
      </AppShell.Main>

      {showPanel && (
        <AppShell.Aside aria-label={t("telemetry.title")}>
          {/* Outside every route boundary: its own, with a way to close it (the open flag survives a reload). */}
          <ErrorBoundary fallback={<TelemetryPanelCrash onClose={closePanel} />}>
            <Suspense fallback={<LoadingBlock />}>
              <TelemetryPanel onClose={closePanel} />
            </Suspense>
          </ErrorBoundary>
        </AppShell.Aside>
      )}
    </AppShell>
  );
}

export default function App() {
  return (
    <Suspense fallback={<RouteFallback />}>
      <Routes>
        <Route
          path="/login"
          element={
            <RedirectIfAuthed>
              <Login />
            </RedirectIfAuthed>
          }
        />
        <Route
          path="/reset-password"
          element={
            <RedirectIfAuthed>
              <ResetPassword />
            </RedirectIfAuthed>
          }
        />
        {/* Not behind RedirectIfAuthed: a signed-in device may finish a reset (the page clears its local session on success). */}
        <Route path="/reset-password/confirm" element={<ConfirmPasswordReset />} />
        <Route element={<RequireAuth />}>
          <Route element={<Shell />}>
            <Route index element={<Home />} />
            <Route path="search" element={<Search />} />
            <Route path="rooms" element={<Rooms />} />
            <Route path="rooms/new" element={<CreateRoom />} />
            <Route path="rooms/:id" element={<RoomHome />} />
            <Route path="rooms/:id/threads/new" element={<RoomHome isNew />} />
            <Route path="rooms/:id/threads/:number" element={<RoomHome />} />
            <Route path="rooms/:id/search" element={<RoomSearch />} />
            <Route path="rooms/:id/members" element={<RoomMembers />} />
            <Route path="rooms/:id/workflow" element={<RoomWorkflow />} />
            <Route path="rooms/:id/settings" element={<RoomSettings />} />
            {/* A profile any signed-in member may open (1.2 D7): outside RequireAdmin; what it lists the server scopes to the viewer. */}
            <Route path="people/:id" element={<Person />} />
            <Route path="teams" element={<Teams />} />
            <Route path="teams/:id" element={<TeamDetails />} />
            {/* The management surface — ADMIN only, guarded once here (the pages no longer redirect themselves). */}
            <Route element={<RequireAdmin />}>
              <Route path="users" element={<Users />} />
              <Route path="users/new" element={<CreateUser />} />
              <Route path="users/:id/edit" element={<EditUser />} />
              <Route path="users/:id/features" element={<UserFeatures />} />
              <Route path="feature-flags" element={<FeatureFlags />} />
              <Route path="workflow-templates" element={<WorkflowTemplates />} />
              <Route path="workflow-templates/new" element={<CreateWorkflowTemplate />} />
              <Route path="workflow-templates/:id/edit" element={<EditWorkflowTemplate />} />
            </Route>
            <Route path="change-password" element={<ChangePassword />} />
            <Route path="notification-preferences" element={<NotificationPreferences />} />
            <Route path="changelog" element={<Changelog />} />
            {/* The authenticated catch-all — LAST child, never feature-gated. */}
            <Route path="*" element={<NotFound />} />
          </Route>
        </Route>
      </Routes>
    </Suspense>
  );
}
