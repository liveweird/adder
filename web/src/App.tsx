import { lazy, Suspense, useEffect, useId, useMemo, useState } from "react";
import { ActionIcon, AppShell, Burger, Drawer, Group, Text, Tooltip, useMantineTheme } from "@mantine/core";
import { useDisclosure, useMediaQuery } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand } from "@tabler/icons-react";
import { Link as RouterLink, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { RedirectIfAuthed, RequireAdmin, RequireAuth, useAdmin } from "./auth";
import { getRoomOrganization } from "./api/roomOrganization";
import BrandLogo from "./components/BrandLogo";
import CommandPalette from "./components/CommandPalette";
import LiveStatus from "./components/LiveStatus";
import LoadingBlock from "./components/LoadingBlock";
import NotificationsButton from "./components/NotificationsButton";
import ThreadDraftsProvider from "./components/ThreadDraftsProvider";
import TelemetryPanelCrash from "./components/TelemetryPanelCrash";
import UserMenu from "./components/UserMenu";
import WorkspaceNav from "./components/WorkspaceNav";
import ErrorBoundary, { RouteErrorBoundary } from "./components/ErrorBoundary";
import { KnownNamesContext, useSessionKnownNames } from "./utils/knownNames";
import { activeNavPath, homePath, visibleSections } from "./utils/navigation";
import { roomOrganizationKey } from "./utils/roomKeys";
import { roomOrganizationPath, roomPath } from "./utils/roomLinks";
import { unseenByRoom } from "./utils/unread";
import { useCloseOnPageChange } from "./hooks/useCloseOnPageChange";
import { usePrintLifecycle } from "./hooks/usePrintLifecycle";
import { useLiveUpdates } from "./hooks/useLiveUpdates";
import { useRailRooms } from "./hooks/useRailRooms";
import { useSeenSync } from "./hooks/useSeenSync";
import { useTabTitleCount } from "./hooks/useTabTitleCount";
import { useAddressedNotifications, useUnseenNotifications } from "./hooks/useUnseenNotifications";
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
const RoomOrganization = lazy(() => import("./pages/RoomOrganization"));
const CreateRoom = lazy(() => import("./pages/CreateRoom"));
const RoomHome = lazy(() => import("./pages/RoomHome"));
const RoomMembers = lazy(() => import("./pages/RoomMembers"));
const RoomSettings = lazy(() => import("./pages/RoomSettings"));
const RoomWorkflow = lazy(() => import("./pages/RoomWorkflow"));
const Person = lazy(() => import("./pages/Person"));
const Tags = lazy(() => import("./pages/Tags"));
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
  const { breakpoints } = useMantineTheme();
  const desktop = useMediaQuery(`(min-width: ${breakpoints.sm})`, undefined, { getInitialValueInEffect: false });
  const [opened, { open, close }] = useDisclosure();
  const navDrawerOpen = opened && !desktop;
  const [returnNavFocus, setReturnNavFocus] = useState(true);
  const { pathname, search, hash } = useLocation();
  const navId = useId();
  const drawerId = useId();
  const noteNavOpened = useCloseOnPageChange(navDrawerOpen, () => {
    setReturnNavFocus(false);
    close();
  }, "url");
  useEffect(() => {
    if (desktop && opened) close();
  }, [desktop, opened, close]);

  const openNavigation = () => {
    setReturnNavFocus(true);
    noteNavOpened();
    open();
  };
  // Every page prints in the light scheme (the print-aware provider in main.tsx reads what this reports).
  usePrintLifecycle();
  // Desktop navbar collapse — device-level, persisted like the other view settings; the
  // mobile overlay keeps its own `opened` disclosure above and always shows full labels.
  const [navCollapsed, setNavCollapsed] = useStoredState("appShell.navCollapsed", false, isBoolean);
  const rail = navCollapsed;
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
  const { data: organization } = useQuery({ queryKey: roomOrganizationKey, queryFn: getRoomOrganization });
  // The unseen notices ADDRESSED to the caller (a mention of any kind, a hand-over; the bell's own query, shared): their count per room is
  // the `@` number in the rail. A reply or a new thread is in the bell and its badge, never here (0.6 D1).
  const addressed = useAddressedNotifications();
  const mentions = useMemo(() => unseenByRoom(addressed.items), [addressed.items]);
  // The tab's one live socket: it follows the ACTIVE rail rooms and the open room (the archived rail and "All rooms" refresh on focus).
  const liveStatus = useLiveUpdates((railRooms.active.data?.items ?? []).map((room) => room.id));
  // The tab title counts the bell's number (1.2 D8) while the socket can keep it current, and the person's other tabs tell this one when notices were seen (D9).
  const unseen = useUnseenNotifications();
  useTabTitleCount(unseen.total, liveStatus);
  useSeenSync(liveStatus === "idle");
  // The rooms join the leaves in ONE longest-prefix resolution, so /rooms/12/members lights room 12, not "All rooms".
  const roomLeaves = [...(railRooms.active.data?.items ?? []), ...(showArchived ? (railRooms.archived.data?.items ?? []) : [])].map((room) => ({
    to: roomPath(room.id),
  }));
  const activeTo = activeNavPath(pathname, [...sections.flatMap((section) => section.items), ...roomLeaves]);

  const navigation = { sections, activeTo, railRooms, organization, mentions, showArchived, onShowArchivedChange: setShowArchived };

  return (
    <AppShell
      header={{ height: 48 }}
      navbar={{ width: { base: 240, sm: rail ? 64 : 240 }, breakpoint: "sm", collapsed: { mobile: true } }}
      // 420 px beside the page from `lg` up (the content gives way: at 1200 px the 240 px navbar and the panel still leave ~540 px);
      // below it the panel covers the page edge to edge, since a pushed 420 px next to the navbar would leave a sliver.
      aside={{ width: 420, breakpoint: "lg", collapsed: { desktop: !showPanel, mobile: !showPanel } }}
      padding="md"
    >
      <AppShell.Header>
        <a href="#main-content" className="skip-link">
          {t("appShell.skipToContent")}
        </a>
        <div className={classes.appbar} data-rail={rail || undefined}>
          <Group className={classes.brandGroup} gap={6} wrap="nowrap">
            <Burger
              opened={navDrawerOpen}
              onClick={openNavigation}
              hiddenFrom="sm"
              size="sm"
              aria-label={t("appShell.toggleMobileNav")}
              aria-expanded={navDrawerOpen}
              aria-controls={navDrawerOpen ? drawerId : undefined}
              aria-haspopup="dialog"
            />
            <Tooltip label={t("appShell.toggleNav")} position="bottom-start" withinPortal>
              <ActionIcon
                visibleFrom="sm"
                size="lg"
                onClick={() => setNavCollapsed(!navCollapsed)}
                aria-label={t("appShell.toggleNav")}
                aria-expanded={!navCollapsed}
                aria-controls={navId}
                data-expanded={!navCollapsed || undefined}
              >
                {navCollapsed ? <IconLayoutSidebarLeftExpand size={18} /> : <IconLayoutSidebarLeftCollapse size={18} />}
              </ActionIcon>
            </Tooltip>
            <RouterLink className={classes.brandLink} to={homePath}>
              <BrandLogo />
              <Text span fw={650} size="md" className={classes.brandText}>{t("appShell.brand")}</Text>
            </RouterLink>
          </Group>
          <div className={classes.headerSearch}>
            <CommandPalette shortcutEnabled={!navDrawerOpen} />
          </div>
          <Group className={classes.headerActions} gap={6} wrap="nowrap">
            <LiveStatus status={liveStatus} />
            <NotificationsButton />
            <UserMenu />
          </Group>
        </div>
      </AppShell.Header>

      <AppShell.Navbar id={navId} component="div" visibleFrom="sm" p={8}>
        <WorkspaceNav {...navigation} rail={rail} />
      </AppShell.Navbar>

      <Drawer
        attributes={{ content: { id: drawerId } }}
        opened={navDrawerOpen}
        onClose={close}
        size="min(20rem, 88vw)"
        title={t("appShell.navigation")}
        returnFocus={returnNavFocus && !desktop}
        closeButtonProps={{ "aria-label": t("common.action.close") }}
        classNames={{ content: classes.navDrawer, body: classes.navDrawerBody, title: classes.navDrawerTitle }}
      >
        <WorkspaceNav {...navigation} onNavigate={(to) => { if (to === `${pathname}${search}${hash}`) close(); }} />
      </Drawer>

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
            <Route path={roomOrganizationPath} element={<RoomOrganization />} />
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
              <Route path="tags" element={<Tags />} />
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
