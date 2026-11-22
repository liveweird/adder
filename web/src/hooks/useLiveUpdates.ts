import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { matchPath, useLocation } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { API_BASE } from "../api/http";
import { mintLiveTicket } from "../api/live";
import { getSessionSnapshot } from "../api/session";
import { bindBrowserSignals, browserClock, LiveClient, type LiveDependencies, type LiveStatus } from "../utils/liveClient";
import { createLiveInvalidator } from "../utils/liveKeys";
import { liveUrl } from "../utils/liveProtocol";
import { reportLiveStatus } from "../utils/queryClient";
import { parseRoomId } from "../utils/roomLinks";
import { subscribeLogicalSession } from "../utils/sessionQueryCache";

const currentIdentity = () => getSessionSnapshot().identity;

/** The rooms to follow: the open room first (it is the one being read), then the rail's, each once. */
function wantedRooms(openRoom: number | null, railRoomIds: readonly number[]): number[] {
  return [...new Set(openRoom === null ? railRoomIds : [openRoom, ...railRoomIds])];
}

/** A module-level function, so the effect that owns the client does not see a new dependency on every render. */
const openBrowserSocket: LiveDependencies["socketFactory"] = (url) => new WebSocket(url);

type LiveOverrides = Partial<Pick<LiveDependencies, "socketFactory" | "clock" | "mintTicket">>;

/**
 * The tab's ONE live socket (called in the shell, inside the session boundary): it follows the rail's active rooms and the open room, turns the
 * server's hints into targeted query invalidations and reports the connection's status. A new sign-in is a new socket, sign-out closes it, a
 * token rotation keeps it (the identity does not change). `overrides` is for tests (a fake socket, clock and ticket).
 */
export function useLiveUpdates(railRoomIds: readonly number[], overrides: LiveOverrides = {}): LiveStatus {
  const queryClient = useQueryClient();
  const { pathname } = useLocation();
  const identity = useSyncExternalStore(subscribeLogicalSession, currentIdentity, () => null);
  const openRoom = parseRoomId(matchPath("/rooms/:id/*", pathname)?.params.id);
  const wanted = wantedRooms(openRoom, railRoomIds);
  const wantedKey = wanted.join(",");
  const [status, setStatus] = useState<LiveStatus>("connecting");
  const clientRef = useRef<LiveClient | null>(null);
  // The effects below read the latest set when they run; their own dependencies are the primitives that mean "a change".
  const wantedRef = useRef(wanted);
  useEffect(() => {
    wantedRef.current = wanted;
  });
  const { clock = browserClock, socketFactory = openBrowserSocket, mintTicket = mintLiveTicket } = overrides;

  useEffect(() => {
    if (identity === null) return;
    const invalidator = createLiveInvalidator(queryClient, clock);
    const client = new LiveClient({
      url: liveUrl(window.location, API_BASE),
      socketFactory,
      clock,
      mintTicket,
      onFrame: invalidator.handle,
      onStatus: (next) => {
        reportLiveStatus(next);
        setStatus(next);
      },
      // The tab's quiet flag, not the socket's status: it is told BEFORE the status and the wake-up `resync`, so that backfill is not filtered.
      onQuiet: invalidator.setQuiet,
    });
    clientRef.current = client;
    client.setRooms(wantedRef.current);
    client.start();
    const unbind = bindBrowserSignals(client, window, document, navigator);
    return () => {
      unbind();
      client.stop();
      invalidator.dispose();
      clientRef.current = null;
    };
  }, [queryClient, identity, clock, socketFactory, mintTicket]);

  useEffect(() => {
    clientRef.current?.setRooms(wantedRef.current);
  }, [wantedKey, identity]);

  // A refusal is remembered only while the same room is open: coming back to it (or any other) asks again.
  useEffect(() => {
    clientRef.current?.forgetRefused();
  }, [openRoom]);

  return identity === null ? "stopped" : status;
}
