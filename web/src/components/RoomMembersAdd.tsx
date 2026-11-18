import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Box, Button, Group, Stack, Title } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { IconUserPlus, IconUsersGroup } from "@tabler/icons-react";
import { addRoomMembers, type RoomResponse } from "../api/rooms";
import { getTeam } from "../api/teams";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "./ConfirmActionModal";
import FormErrorAlert from "./FormErrorAlert";
import type { PickedOption } from "./SearchPicker";
import TeamSelect from "./TeamSelect";
import UserDirectorySelect from "./UserDirectorySelect";

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

/**
 * The room's add controls (shown to its OWNER or an ADMIN; the server guards): one person from the user directory, or a whole team
 * after a confirm that states how many people it holds today (a team is a snapshot — a later roster change does not follow). People
 * already on the current page are left out of the picker (at render); the server's add is idempotent for everyone else and counts
 * them as already members.
 */
export default function RoomMembersAdd({ room, memberValues }: { room: RoomResponse; memberValues: ReadonlySet<string> }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const refresh = () => refreshQueriesAfterMutation(queryClient, ["rooms"]);
  const [person, setPerson] = useState<PickedOption | null>(null);
  const [team, setTeam] = useState<PickedOption | null>(null);
  // `plan` outlives the dialog (its text stays put through the close transition); `confirming` is what opens it.
  const [plan, setPlan] = useState<TeamPlan | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [emptyTeam, setEmptyTeam] = useState(false);

  const addPerson = useMutation({
    mutationFn: (userId: number) => addRoomMembers(room.id, { userIds: [userId] }),
    onSuccess: async (result) => {
      setPerson(null);
      showSuccessToast(t(result.added > 0 ? "rooms.members.toast.added" : "rooms.members.toast.alreadyMember"));
      await refresh();
    },
    // A 403, 404 or 409 says the caller's standing moved (demoted, removed): re-read the room so the controls follow.
    onError: async (failure) => {
      if (isAccessRefusal(failure)) await refresh();
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
    mutationFn: (teamId: number) => addRoomMembers(room.id, { teamId }),
    onSuccess: async (result) => {
      setConfirming(false);
      setTeam(null);
      showSuccessToast(t("rooms.members.toast.teamAdded", { added: result.added, already: result.alreadyMembers }));
      await refresh();
    },
    // The failure shows inline under the controls, not inside the closed dialog.
    onError: async (failure) => {
      setConfirming(false);
      if (isAccessRefusal(failure)) await refresh();
    },
  });

  function clearFeedback() {
    addPerson.reset();
    inspectTeam.reset();
    addTeam.reset();
    setEmptyTeam(false);
  }

  return (
    <Stack gap="sm">
      <Title order={3} size="h4">
        {t("rooms.members.addTitle")}
      </Title>
      <Group align="flex-end" gap="xl" wrap="wrap">
        <Group align="flex-end" gap="sm" wrap="wrap">
          <Box w="100%" maw={340}>
            <UserDirectorySelect
              label={t("rooms.members.addPersonLabel")}
              picked={person}
              exclude={memberValues}
              onPick={(next) => {
                clearFeedback();
                setPerson(next);
              }}
            />
          </Box>
          <Button
            leftSection={<IconUserPlus size={16} />}
            disabled={!person}
            loading={addPerson.isPending}
            onClick={() => {
              clearFeedback();
              if (person) addPerson.mutate(Number(person.value));
            }}
          >
            {t("rooms.members.addPerson")}
          </Button>
        </Group>
        <Group align="flex-end" gap="sm" wrap="wrap">
          <Box w="100%" maw={340}>
            <TeamSelect
              label={t("rooms.members.addTeamLabel")}
              description={t("rooms.members.addTeamHint")}
              picked={team}
              onPick={(next) => {
                clearFeedback();
                setTeam(next);
              }}
            />
          </Box>
          <Button
            leftSection={<IconUsersGroup size={16} />}
            disabled={!team}
            loading={inspectTeam.isPending}
            onClick={() => {
              clearFeedback();
              if (team) inspectTeam.mutate(Number(team.value));
            }}
          >
            {t("rooms.members.addTeam")}
          </Button>
        </Group>
      </Group>
      {addPerson.isError && <FormErrorAlert title={t("rooms.members.addFailed")}>{saveErrorMessage(addPerson.error, t, ADD_ERRORS)}</FormErrorAlert>}
      {addTeam.isError && <FormErrorAlert title={t("rooms.members.addFailed")}>{saveErrorMessage(addTeam.error, t, ADD_ERRORS)}</FormErrorAlert>}
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
        onConfirm={() => plan && addTeam.mutate(plan.id)}
        loading={addTeam.isPending}
      />
    </Stack>
  );
}
