import { Component, type ReactNode } from "react";
import { Alert, Button, Center, Stack, Text } from "@mantine/core";
import { IconAlertTriangle } from "@tabler/icons-react";
import { matchPath, useLocation } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { parseRoomId, parseThreadNumber, roomPath, roomsPath } from "../utils/roomLinks";

interface BoundaryProps {
  children: ReactNode;
  fallback?: ReactNode;
  recoveryPath?: string;
}

interface BoundaryState {
  failed: boolean;
  recoveryPath?: string;
}

/**
 * The render-crash safety net (v2.22.0) — before it, any render-time throw unmounted the
 * whole React tree into a white screen. Two mounts: `RouteErrorBoundary` wraps the routed
 * content inside the AppShell (header/nav survive a page crash, and navigation clears the
 * failure), and a plain `ErrorBoundary` sits at
 * the root in main.tsx as the last resort (no router hooks there). It also backstops a
 * lazy-chunk load failure when the one-shot reload in main.tsx wasn't enough. A caller whose surface has its own way out (the
 * telemetry panel's "Close panel") passes `fallback`.
 */
export default class ErrorBoundary extends Component<BoundaryProps, BoundaryState> {
  state = { failed: false, recoveryPath: this.props.recoveryPath };

  static getDerivedStateFromProps({ recoveryPath }: BoundaryProps, state: BoundaryState): BoundaryState | null {
    return state.recoveryPath === recoveryPath ? null : { failed: false, recoveryPath };
  }

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true };
  }

  render() {
    // React already logs the caught error itself — the boundary only swaps the UI.
    return this.state.failed ? (this.props.fallback ?? <CrashFallback />) : this.props.children;
  }
}

export function RouteErrorBoundary({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  const pane = matchPath(`${roomsPath}/:id/threads/:number`, pathname);
  const roomId = parseRoomId(pane?.params.id);
  // The room list and its conversation share a page. Keep its healthy header and list mounted when the pane closes or switches.
  const key = roomId !== null && parseThreadNumber(pane?.params.number) !== null ? roomPath(roomId) : pathname;
  return <ErrorBoundary key={key} recoveryPath={pathname}>{children}</ErrorBoundary>;
}

function CrashFallback() {
  const { t } = useTranslation();
  return (
    <Center mih={200} p="xl">
      <Alert
        color="red"
        variant="light"
        icon={<IconAlertTriangle />}
        title={t("common.errorBoundary.title")}
        maw={480}
      >
        <Stack gap="sm" align="flex-start">
          <Text size="sm">{t("common.errorBoundary.message")}</Text>
          <Button color="red" variant="light" onClick={() => window.location.reload()}>
            {t("common.errorBoundary.reload")}
          </Button>
        </Stack>
      </Alert>
    </Center>
  );
}
