import { useNavigate } from "react-router-dom";
import { useMutation, useQueryClient, type QueryClient, type QueryKey } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { joinPublicRoom, type RoomResponse } from "../api/rooms";
import { getSessionSnapshot, isSameSessionIdentity, type SessionSnapshot } from "../api/session";
import { roomPath } from "../utils/roomLinks";
import { homeKey, roomKeys, roomOrganizationKey } from "../utils/roomKeys";
import { unresolvedThreadReferenceKeys } from "../utils/threadReferenceCache";
import { showSuccessToast } from "../utils/toast";

type PublicJoinInput = {
  roomId: number;
  session: SessionSnapshot;
  wasReadableNonMember: boolean;
  routeToken: symbol;
};

export type PublicRoomEnrollment = {
  join(): void;
  pending: boolean;
  failure: unknown | null;
};

function recheckUnresolvedReferences(queryClient: QueryClient, input: PublicJoinInput): void {
  const keys = unresolvedThreadReferenceKeys(queryClient, input.roomId);
  if (keys.length === 0 || !isSameSessionIdentity(input.session, getSessionSnapshot())) return;
  void Promise.all(keys.map((queryKey) => queryClient.cancelQueries({ queryKey, exact: true }))).then(() => {
    if (!isSameSessionIdentity(input.session, getSessionSnapshot())) return;
    keys.forEach((queryKey) => { void queryClient.invalidateQueries({ queryKey, exact: true }); });
  });
}

/** Response-only membership fallback: narrow, authorization-guarded, and independent of the permission-dependent page body. */
async function refreshAfterPublicJoin(queryClient: QueryClient, input: PublicJoinInput): Promise<{ current: boolean; joined: boolean }> {
  const keys: QueryKey[] = [
    roomKeys.detail(input.roomId),
    roomKeys.access(input.roomId),
    roomKeys.members(input.roomId),
    roomKeys.rail,
    roomKeys.list,
    homeKey,
    roomOrganizationKey,
  ];
  if (input.wasReadableNonMember) {
    keys.push(roomKeys.threadList(input.roomId), roomKeys.threadDetails(input.roomId));
  }
  if (!isSameSessionIdentity(input.session, getSessionSnapshot())) return { current: false, joined: false };
  await Promise.all(keys.map((queryKey) => queryClient.cancelQueries({ queryKey })));
  if (!isSameSessionIdentity(input.session, getSessionSnapshot())) return { current: false, joined: false };
  await Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
  if (!isSameSessionIdentity(input.session, getSessionSnapshot())) return { current: false, joined: false };
  recheckUnresolvedReferences(queryClient, input);
  const room = queryClient.getQueryState<RoomResponse>(roomKeys.detail(input.roomId));
  return {
    current: true,
    joined: room?.status === "success" && room.data !== undefined && room.data.role !== null,
  };
}

/** Public enrollment hosted by the stable RoomPage route, not by its preview/content branch. */
export function usePublicRoomEnrollment(
  roomId: number,
  wasReadableNonMember: boolean,
  routeToken: symbol,
  activeRouteIdentity: () => symbol | null,
): PublicRoomEnrollment {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const enrollment = useMutation({
    mutationFn: async (input: PublicJoinInput) => {
      await joinPublicRoom(input.roomId);
      return { ...input, ...await refreshAfterPublicJoin(queryClient, input) };
    },
    onSuccess: ({ current, joined, roomId: joinedRoom, session, routeToken: submittedRoute }) => {
      if (!current || !joined || activeRouteIdentity() !== submittedRoute || !isSameSessionIdentity(session, getSessionSnapshot())) return;
      showSuccessToast(t("rooms.access.joined"));
      navigate(roomPath(joinedRoom), { replace: true });
    },
  });
  const ownsCurrentVisit = enrollment.variables !== undefined && routeToken === enrollment.variables.routeToken &&
    isSameSessionIdentity(enrollment.variables.session, getSessionSnapshot());
  return {
    join: () => enrollment.mutate({
      roomId,
      session: getSessionSnapshot(),
      wasReadableNonMember,
      routeToken,
    }),
    pending: enrollment.isPending && ownsCurrentVisit,
    failure: enrollment.isError && ownsCurrentVisit ? enrollment.error : null,
  };
}
