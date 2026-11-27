import { useLayoutEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Box, Button, Group, Stack, ThemeIcon, Title } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { IconUserPlus, IconUsersGroup } from "@tabler/icons-react";
import { addRoomMembers, type RoomResponse } from "../api/rooms";
import { getSessionSnapshot, isSameSessionIdentity, type SessionSnapshot } from "../api/session";
import { getTeam } from "../api/teams";
import { beginRoomMembershipEcho, refreshRoomMembership, settleRoomMembershipRefresh } from "../utils/roomMembershipRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "./ConfirmActionModal";
import FormErrorAlert from "./FormErrorAlert";
import type { PickedOption } from "./SearchPicker";
import TeamSelect from "./TeamSelect";
import UserDirectorySelect from "./UserDirectorySelect";
import classes from "./RoomMembersAdd.module.css";

const ADD_ERRORS = {
  forbidden: "rooms.members.addForbidden",
  notFound: "rooms.members.addGone",
  conflict: "rooms.members.addFull",
  tooManyRequests: "common.error.tooManyRequests",
  invalid: "rooms.members.addInvalid",
  failedStatus: "common.error.actionFailedStatus",
  failed: "common.error.actionFailed",
} as const;

const TEAM_READ_ERRORS = {
  notFound: "rooms.members.addInvalid",
  failedStatus: "common.error.actionFailedStatus",
  failed: "common.error.actionFailed",
} as const;

/** A team about to be added: what the confirm states (its active roster as of the read just made). */
type TeamPlan = { id: number; name: string; count: number };
type AddInput = { roomId: number; session: SessionSnapshot; body: Parameters<typeof addRoomMembers>[1] };

/**
 * The room's add controls (shown to its OWNER or an ADMIN; the server guards): one person from the user directory, or a whole team
 * after a confirm that states how many people it holds today (a team is a snapshot — a later roster change does not follow). People
 * already on the current page are left out of the picker (at render); the server's add is idempotent for everyone else and counts
 * them as already members.
 */
export default function RoomMembersAdd({
  room,
  memberValues,
  onAddFailure,
}: {
  room: RoomResponse;
  memberValues: ReadonlySet<string>;
  /** The words of a failed add, or null when the failure is cleared. The page shows them, so they outlive these controls (a 403 removes them). */
  onAddFailure: (message: string | null) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const activeRoom = useRef<number | null>(room.id);
  useLayoutEffect(() => {
    activeRoom.current = room.id;
    return () => { activeRoom.current = null; };
  }, [room.id]);
  const [person, setPerson] = useState<PickedOption | null>(null);
  const [team, setTeam] = useState<PickedOption | null>(null);
  // `plan` outlives the dialog (its text stays put through the close transition); `confirming` is what opens it.
  const [plan, setPlan] = useState<TeamPlan | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [emptyTeam, setEmptyTeam] = useState(false);

  const addMembers = async ({ roomId, session, body }: AddInput) => {
    const echo = beginRoomMembershipEcho(queryClient, roomId, "room.member_added");
    let result: Awaited<ReturnType<typeof addRoomMembers>>;
    try {
      result = await addRoomMembers(roomId, body, echo?.id);
    } catch (error) {
      echo?.cancel();
      throw error;
    }
    const current = await settleRoomMembershipRefresh(queryClient, session, echo);
    return { result, roomId, current };
  };
  const addPerson = useMutation({
    mutationFn: addMembers,
    onSuccess: ({ result, roomId, current }) => {
      if (!current || activeRoom.current !== roomId) return;
      setPerson(null);
      showSuccessToast(t(result.added > 0 ? "rooms.members.toast.added" : "rooms.members.toast.alreadyMember"));
    },
    // A 403, 404 or 409 says the caller's standing moved (demoted, removed): re-read the room so the controls follow.
    onError: async (failure, input) => {
      if (activeRoom.current !== input.roomId || !isSameSessionIdentity(input.session, getSessionSnapshot())) return;
      onAddFailure(saveErrorMessage(failure, t, ADD_ERRORS));
      if (isAccessRefusal(failure)) await refreshRoomMembership(queryClient, input.session);
    },
  });
  // Reads the team's roster for the confirm's count (the registry is open to every signed-in user).
  const inspectTeam = useMutation({
    mutationFn: (teamId: number) => queryClient.fetchQuery({ queryKey: ["teams", "detail", teamId], queryFn: () => getTeam(teamId), staleTime: 0 }),
    onSuccess: (found) => {
      if (found.members.length === 0) {
        setEmptyTeam(true);
        return;
      }
      setPlan({ id: found.id, name: found.name, count: found.members.length });
      setConfirming(true);
    },
  });
  const addTeam = useMutation({
    mutationFn: addMembers,
    onSuccess: ({ result, roomId, current }) => {
      if (!current || activeRoom.current !== roomId) return;
      setConfirming(false);
      setTeam(null);
      showSuccessToast(t("rooms.members.toast.teamAdded", { added: result.added, already: result.alreadyMembers }));
    },
    // The failure shows inline under the controls, not inside the closed dialog.
    onError: async (failure, input) => {
      if (activeRoom.current !== input.roomId || !isSameSessionIdentity(input.session, getSessionSnapshot())) return;
      setConfirming(false);
      onAddFailure(saveErrorMessage(failure, t, ADD_ERRORS));
      if (isAccessRefusal(failure)) await refreshRoomMembership(queryClient, input.session);
    },
  });

  function clearFeedback() {
    addPerson.reset();
    inspectTeam.reset();
    addTeam.reset();
    onAddFailure(null);
    setEmptyTeam(false);
  }

  return (
    <Stack gap="md" component="section" aria-labelledby="room-members-add" className={classes.section}>
      <Group gap="sm" wrap="nowrap">
        <ThemeIcon variant="light" color="adder" radius="md" size="lg" aria-hidden="true">
          <IconUserPlus size={18} />
        </ThemeIcon>
        <Title id="room-members-add" order={4} size="h4" tabIndex={-1} className={classes.heading}>
          {t("rooms.members.addTitle")}
        </Title>
      </Group>
      <div className={classes.options}>
        <Box
          component="form"
          className={classes.option}
          aria-label={t("rooms.members.addPersonLabel")}
          onSubmit={(event) => {
            event.preventDefault();
            clearFeedback();
            if (person) addPerson.mutate({ roomId: room.id, session: getSessionSnapshot(), body: { userIds: [Number(person.value)] } });
          }}
        >
          <Stack gap="sm">
            <UserDirectorySelect
              label={t("rooms.members.addPersonLabel")}
              picked={person}
              exclude={memberValues}
              onPick={(next) => {
                clearFeedback();
                setPerson(next);
              }}
            />
            <Button type="submit" leftSection={<IconUserPlus size={16} />} disabled={!person} loading={addPerson.isPending} className={classes.submit}>
              {t("rooms.members.addPerson")}
            </Button>
          </Stack>
        </Box>
        <Box
          component="form"
          className={classes.option}
          aria-label={t("rooms.members.addTeamLabel")}
          onSubmit={(event) => {
            event.preventDefault();
            clearFeedback();
            if (team) inspectTeam.mutate(Number(team.value));
          }}
        >
          <Stack gap="sm">
            <TeamSelect
              label={t("rooms.members.addTeamLabel")}
              description={t("rooms.members.addTeamHint")}
              picked={team}
              onPick={(next) => {
                clearFeedback();
                setTeam(next);
              }}
            />
            <Button type="submit" leftSection={<IconUsersGroup size={16} />} disabled={!team} loading={inspectTeam.isPending} className={classes.submit}>
              {t("rooms.members.addTeam")}
            </Button>
          </Stack>
        </Box>
      </div>
      {inspectTeam.isError && <FormErrorAlert title={t("rooms.members.teamLoadFailed")}>{saveErrorMessage(inspectTeam.error, t, TEAM_READ_ERRORS)}</FormErrorAlert>}
      {emptyTeam && <FormErrorAlert title={t("rooms.members.addFailed")}>{t("rooms.members.teamEmpty")}</FormErrorAlert>}

      <ConfirmActionModal
        opened={confirming}
        onClose={() => setConfirming(false)}
        title={t("rooms.members.teamTitle")}
        message={plan ? t("rooms.members.teamBody", { team: plan.name, count: plan.count }) : null}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t("rooms.members.addTeam")}
        confirmColor="adder"
        onConfirm={() => plan && addTeam.mutate({ roomId: room.id, session: getSessionSnapshot(), body: { teamId: plan.id } })}
        loading={addTeam.isPending}
      />
    </Stack>
  );
}
