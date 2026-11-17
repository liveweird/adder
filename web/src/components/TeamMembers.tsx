import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Box, Button, Stack, Table, Text, Title } from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { IconUserMinus, IconUserPlus, IconUsersGroup } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { addTeamMember, removeTeamMember, type TeamMember, type TeamResponse } from "../api/teams";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import ConfirmActionModal from "./ConfirmActionModal";
import EmptyState from "./EmptyState";
import FormErrorAlert from "./FormErrorAlert";
import type { PickedOption } from "./SearchPicker";
import UserDirectorySelect from "./UserDirectorySelect";

const ROSTER_ERRORS = {
  forbidden: "teams.members.forbidden",
  notFound: "teams.members.gone",
  failedStatus: "common.error.actionFailedStatus",
  failed: "common.error.actionFailed",
} as const;

/**
 * A team's roster (the Members section of `/teams/:id`). Everyone reads it; `canManage` (an ADMIN or the team's manager —
 * the server's `requireTeamManagerOrAdmin` is the rule, this is UX) adds the directory picker and the per-row remove.
 * Current members are left out of the picker's options (at render, so the cached search never goes stale); the add is idempotent
 * server-side anyway.
 */
export default function TeamMembers({ team, canManage }: { team: TeamResponse; canManage: boolean }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const refresh = () => refreshQueriesAfterMutation(queryClient, ["teams"]);
  const [picked, setPicked] = useState<PickedOption | null>(null);
  // `removing` outlives the dialog: the message stays put through the close transition, `confirming` is what opens it.
  const [removing, setRemoving] = useState<TeamMember | null>(null);
  const [confirming, setConfirming] = useState(false);
  const memberValues = new Set(team.members.map((m) => String(m.userId)));

  const add = useMutation({
    mutationFn: (userId: number) => addTeamMember(team.id, userId),
    onSuccess: async () => {
      setPicked(null);
      showSuccessToast(t("teams.members.toast.added"));
      await refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (member: TeamMember) => removeTeamMember(team.id, member.userId),
    onSuccess: async () => {
      setConfirming(false);
      showSuccessToast(t("teams.members.toast.removed"));
      await refresh();
    },
    // The failure shows inline under the section heading, not inside the closed dialog. A 404 means the roster moved on
    // (somebody else removed them): show the truth.
    onError: async (error) => {
      setConfirming(false);
      if (error instanceof ApiError && error.status === 404) await refresh();
    },
  });

  return (
    <Stack gap="sm">
      <Title order={3} size="h4">
        {t("teams.members.title")}
      </Title>
      <Box maw={CONTENT_MAX_WIDTH}>
        <Stack>
          {canManage && (
            <Stack gap="xs" align="flex-start">
              <Box w="100%" maw={360}>
                <UserDirectorySelect
                  label={t("teams.members.addLabel")}
                  picked={picked}
                  exclude={memberValues}
                  onPick={(next) => {
                    add.reset();
                    setPicked(next);
                  }}
                />
              </Box>
              <Button
                leftSection={<IconUserPlus size={16} />}
                disabled={!picked}
                loading={add.isPending}
                onClick={() => {
                  remove.reset();
                  if (picked) add.mutate(Number(picked.value));
                }}
              >
                {t("teams.members.add")}
              </Button>
            </Stack>
          )}
          {add.isError && (
            <FormErrorAlert title={t("teams.members.addFailed")}>
              {saveErrorMessage(add.error, t, { ...ROSTER_ERRORS, conflict: "teams.members.full" })}
            </FormErrorAlert>
          )}
          {remove.isError && (
            <FormErrorAlert title={t("teams.members.removeFailed")}>{saveErrorMessage(remove.error, t, { ...ROSTER_ERRORS, notFound: "teams.members.removeGone" })}</FormErrorAlert>
          )}
          {team.members.length === 0 ? (
            <EmptyState icon={IconUsersGroup} label={t("teams.members.empty")} />
          ) : (
            <Table aria-label={t("teams.members.tableAria", { name: team.name })}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("common.field.name")}</Table.Th>
                  <Table.Th>{t("common.field.email")}</Table.Th>
                  {canManage && <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {team.members.map((member) => (
                  <Table.Tr key={member.userId}>
                    <Table.Td>
                      <Text size="sm" fw={500}>
                        {member.name}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{member.email}</Text>
                    </Table.Td>
                    {canManage && (
                      <Table.Td style={{ width: 1 }} ta="right">
                        <ActionIcon
                          color="red"
                          aria-label={t("teams.members.removeAria", { name: member.name })}
                          onClick={() => {
                            add.reset();
                            remove.reset();
                            setRemoving(member);
                            setConfirming(true);
                          }}
                        >
                          <IconUserMinus size={16} />
                        </ActionIcon>
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Stack>
      </Box>

      <ConfirmActionModal
        opened={confirming}
        onClose={() => setConfirming(false)}
        title={t("teams.members.removeTitle")}
        message={removing ? t("teams.members.removeBody", { name: removing.name, team: team.name }) : null}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t("teams.members.remove")}
        onConfirm={() => removing && remove.mutate(removing)}
        loading={remove.isPending}
      />
    </Stack>
  );
}
