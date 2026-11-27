import { useEffect, useRef, useState } from "react";
import { Button, Group, Paper, Stack, Table, Text, Title } from "@mantine/core";
import { useMutation, useQuery, useQueryClient, type QueryClient, type QueryKey } from "@tanstack/react-query";
import { IconCheck, IconX } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { useLocation } from "react-router-dom";
import { decideRoomJoinRequest, listRoomJoinRequests, type RoomJoinRequest, type RoomJoinRequestPage } from "../api/rooms";
import { getSessionSnapshot, isSameSessionIdentity, type SessionSnapshot } from "../api/session";
import { browserTimeZone, formatDate } from "../utils/formatDate";
import { homeKey, roomKeys } from "../utils/roomKeys";
import { beginRoomMembershipEcho, settleRoomMembershipRefresh } from "../utils/roomMembershipRefresh";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import ErrorAlert from "./ErrorAlert";
import FormErrorAlert from "./FormErrorAlert";
import LoadingBlock from "./LoadingBlock";
import PaginationBar from "./PaginationBar";
import PersonLink from "./PersonLink";
import classes from "./RoomJoinRequests.module.css";

const PAGE_SIZE = 20;
type Decision = "APPROVE" | "REJECT";
type DecisionInput = { request: RoomJoinRequest; value: Decision; roomId: number; page: number; session: SessionSnapshot };

/** Preserve rejection's narrow refresh while preventing an old credential's callback from invalidating replacement-session caches. */
async function refreshAfterRejection(queryClient: QueryClient, roomId: number, session: SessionSnapshot): Promise<boolean> {
  const keys: QueryKey[] = [roomKeys.joinRequests(roomId), roomKeys.members(roomId), roomKeys.detail(roomId), roomKeys.list, homeKey];
  if (!isSameSessionIdentity(session, getSessionSnapshot())) return false;
  await Promise.all(keys.map((queryKey) => queryClient.cancelQueries({ queryKey })));
  if (!isSameSessionIdentity(session, getSessionSnapshot())) return false;
  await Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
  return isSameSessionIdentity(session, getSessionSnapshot());
}

/** The pending restricted-visible room requests. Only an owner mounts it, matching the server's owner-only queue. */
export default function RoomJoinRequests({ roomId }: { roomId: number }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const mounted = useRef(true);
  const currentRoom = useRef(roomId);
  const currentPage = useRef(page);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  useEffect(() => { currentRoom.current = roomId; }, [roomId]);
  useEffect(() => { currentPage.current = page; }, [page]);
  const { hash } = useLocation();
  const titleRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (hash !== "#room-join-requests-title") return;
    titleRef.current?.focus({ preventScroll: true });
    titleRef.current?.scrollIntoView?.({ block: "start" });
  }, [hash]);
  const query = useQuery({
    queryKey: [...roomKeys.joinRequests(roomId), page],
    queryFn: () => listRoomJoinRequests(roomId, { page, pageSize: PAGE_SIZE, sort: "requestedAt" }),
  });
  const decision = useMutation({
    mutationFn: async (input: DecisionInput) => {
      if (input.value === "REJECT") {
        await decideRoomJoinRequest(input.roomId, input.request.id, input.value);
        return { ...input, current: await refreshAfterRejection(queryClient, input.roomId, input.session) };
      }
      const echo = beginRoomMembershipEcho(queryClient, input.roomId, "room.member_added");
      try {
        await decideRoomJoinRequest(input.roomId, input.request.id, input.value, echo?.id);
      } catch (error) {
        echo?.cancel();
        throw error;
      }
      return { ...input, current: await settleRoomMembershipRefresh(queryClient, input.session, echo) };
    },
    onSuccess: async ({ current, value, roomId: decidedRoom, page: decidedPage, session }) => {
      if (!current || !mounted.current || currentRoom.current !== decidedRoom) return;
      showSuccessToast(t(value === "APPROVE" ? "rooms.members.requests.approved" : "rooms.members.requests.rejected"));
      if (!mounted.current || currentRoom.current !== decidedRoom || currentPage.current !== decidedPage ||
        !isSameSessionIdentity(session, getSessionSnapshot())) return;
      const refreshed = queryClient.getQueryData<RoomJoinRequestPage>([...roomKeys.joinRequests(decidedRoom), decidedPage]);
      if (refreshed) setPage(Math.min(decidedPage, Math.max(1, Math.ceil(refreshed.total / PAGE_SIZE))));
    },
  });

  return (
    <Paper component="section" withBorder p="lg" aria-labelledby="room-join-requests-title">
      <Stack gap="md">
        <Stack gap={2}>
          <Title ref={titleRef} id="room-join-requests-title" order={4} size="h5" tabIndex={-1} className={classes.heading}>
            {t("rooms.members.requests.title")}
          </Title>
          <Text size="sm" c="dimmed">
            {t("rooms.members.requests.description")}
          </Text>
        </Stack>
        {query.isLoading && <LoadingBlock />}
        {query.isError && <ErrorAlert error={query.error} title={t("rooms.members.requests.loadFailed")} />}
        {decision.isError && decision.variables?.roomId === roomId && decision.variables.page === page &&
          isSameSessionIdentity(decision.variables.session, getSessionSnapshot()) && (
          <FormErrorAlert title={t("rooms.members.requests.decisionFailed")}>
            {saveErrorMessage(decision.error, t, {
              retryAfter: true,
              forbidden: "rooms.members.forbidden",
              notFound: "rooms.members.gone",
              conflict: "rooms.access.full",
              failedStatus: "common.error.actionFailedStatus",
              failed: "common.error.actionFailed",
            })}
          </FormErrorAlert>
        )}
        {query.data?.total === 0 && <Text c="dimmed">{t("rooms.members.requests.empty")}</Text>}
        {query.data && query.data.items.length > 0 && (
          <>
            <Table.ScrollContainer minWidth={560}>
              <Table aria-label={t("rooms.members.requests.title")} verticalSpacing="sm">
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t("common.field.name")}</Table.Th>
                    <Table.Th>{t("rooms.members.requests.requestedColumn")}</Table.Th>
                    <Table.Th ta="right">{t("common.table.operations")}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {query.data.items.map((request) => {
                    const acting = decision.isPending && decision.variables?.request.id === request.id;
                    return (
                      <Table.Tr key={request.id}>
                        <Table.Td>
                          <PersonLink id={request.userId} name={request.name} size="sm" fw={600} />
                        </Table.Td>
                        <Table.Td>{formatDate(request.requestedAt, "—", browserTimeZone())}</Table.Td>
                        <Table.Td>
                          <Group justify="flex-end" gap="xs" wrap="nowrap">
                            <Button
                              size="xs"
                              variant="default"
                              leftSection={<IconX size={14} aria-hidden="true" />}
                              disabled={decision.isPending}
                              aria-label={t("rooms.members.requests.rejectAria", { name: request.name })}
                              onClick={() => decision.mutate({
                                request, value: "REJECT", roomId, page, session: getSessionSnapshot(),
                              })}
                            >
                              {t("rooms.members.requests.reject")}
                            </Button>
                            <Button
                              size="xs"
                              leftSection={<IconCheck size={14} aria-hidden="true" />}
                              loading={acting && decision.variables?.value === "APPROVE"}
                              disabled={decision.isPending}
                              aria-label={t("rooms.members.requests.approveAria", { name: request.name })}
                              onClick={() => decision.mutate({
                                request, value: "APPROVE", roomId, page, session: getSessionSnapshot(),
                              })}
                            >
                              {t("rooms.members.requests.approve")}
                            </Button>
                          </Group>
                        </Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
          </>
        )}
        {query.data && query.data.total > 0 && (
          <PaginationBar
            total={query.data.total}
            page={page}
            pageSize={PAGE_SIZE}
            onPageChange={(next) => {
              currentPage.current = next;
              setPage(next);
            }}
          />
        )}
      </Stack>
    </Paper>
  );
}
