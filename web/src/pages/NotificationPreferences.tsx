import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Paper, Stack, Switch, Table, Text, Title } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getNotificationPreferences,
  setNotificationPreferences,
  type NotificationPreferenceKind,
  type NotificationPreferencePair,
  type NotificationType,
} from "../api/notifications";
import { getUserId } from "../api/session";
import ErrorAlert from "../components/ErrorAlert";
import FormErrorAlert from "../components/FormErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

type Channel = NotificationPreferencePair["channel"];

/** The channels of the grid, in column order; `field` is the kind's flag that holds the stored state. */
const CHANNELS = [
  { channel: "IN_APP", field: "inApp", label: "notifications.preferences.columnInApp" },
  { channel: "EMAIL", field: "email", label: "notifications.preferences.columnEmail" },
] as const satisfies readonly { channel: Channel; field: "inApp" | "email"; label: string }[];

/**
 * The grid's groups and the kinds each holds, in the order shown (the kinds themselves come from the server; a kind this build does not
 * group is not drawn, and a test pins that every kind of the contract is in a group). The groups follow who is being told and why.
 */
const GROUPS = [
  { key: "mentions", types: ["MENTIONED", "TEAM_MENTIONED", "WATCHERS_MENTIONED", "EVERYONE_MENTIONED"] },
  { key: "watching", types: ["THREAD_REPLIED", "THREAD_STATUS_CHANGED", "THREAD_CLOSED", "THREAD_REOPENED", "THREAD_OWNER_CHANGED"] },
  { key: "handed", types: ["THREAD_ASSIGNED"] },
  { key: "rooms", types: ["THREAD_CREATED"] },
] as const satisfies readonly { key: string; types: readonly NotificationType[] }[];

/** The key of one switch in the unsaved `overrides` map (the effective state derives from the loaded kinds plus these, so no seeding effect). */
const pairKey = (type: NotificationType, channel: Channel) => `${type}|${channel}`;

/**
 * The caller's own notification preferences (Lettuce's grid, ported): one row per kind of notice, grouped, with an In-app and an Email switch
 * each and "All on / All off" per group and column. There is no master switch and no kind is locked on. Defaults: the app on, email off.
 * Save is a wholesale replace sending EVERY pair, off ones as `disabled` and on ones as `enabled` (`PUT /users/{id}/notification-preferences`):
 * a pair left out of both lists would be reset to its default, so the request always states the whole grid. A deployment with no outbound email says so, and its email column
 * is disabled (the choices are kept).
 */
export default function NotificationPreferences() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const userId = getUserId();
  const [overrides, setOverrides] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);

  const query = useQuery({
    queryKey: ["notificationPreferences", userId],
    queryFn: () => getNotificationPreferences(userId as number),
    enabled: userId !== null,
  });
  const kinds = query.data?.kinds ?? [];
  const emailAvailable = query.data?.emailAvailable ?? false;
  const stored = (kind: NotificationPreferenceKind, field: "inApp" | "email") => kind[field];
  const isOn = (kind: NotificationPreferenceKind, channel: (typeof CHANNELS)[number]) =>
    overrides[pairKey(kind.type, channel.channel)] ?? stored(kind, channel.field);
  const dirty = kinds.some((kind) => CHANNELS.some((channel) => isOn(kind, channel) !== stored(kind, channel.field)));
  const byType = new Map(kinds.map((kind) => [kind.type, kind]));

  const save = useMutation({
    mutationFn: () => {
      const pairs = kinds.flatMap((kind) => CHANNELS.map((channel) => ({ pair: { type: kind.type, channel: channel.channel }, on: isOn(kind, channel) })));
      return setNotificationPreferences(
        userId as number,
        pairs.filter((p) => !p.on).map((p) => p.pair),
        pairs.filter((p) => p.on).map((p) => p.pair),
      );
    },
    onMutate: () => setError(null),
    onSuccess: async () => {
      // Re-read first, then drop the toggles: the switches never flash back to the old stored state.
      await queryClient.invalidateQueries({ queryKey: ["notificationPreferences", userId] });
      setOverrides({});
      showSuccessToast(t("notifications.toast.preferencesSaved"));
    },
    onError: (err) =>
      setError(
        saveErrorMessage(err, t, {
          failedStatus: "notifications.preferences.saveFailedStatus",
          failed: "notifications.preferences.saveFailedNetwork",
        }),
      ),
  });

  function set(types: readonly NotificationType[], channel: Channel, on: boolean) {
    setOverrides((prev) => ({ ...prev, ...Object.fromEntries(types.map((type) => [pairKey(type, channel), on])) }));
  }

  return (
    <Stack gap="md">
      <PageHeader title={t("notifications.preferences.title")} description={t("notifications.preferences.description")} />
      <Paper withBorder p="xl" maw={CONTENT_MAX_WIDTH}>
        {query.isLoading ? (
          <LoadingBlock />
        ) : query.isError || query.data === undefined ? (
          <ErrorAlert error={query.error} />
        ) : (
          <Stack>
            {!emailAvailable && (
              <Alert color="gray" variant="light">
                {t("notifications.preferences.emailOff")}
              </Alert>
            )}
            <Text c="dimmed" size="sm">
              {t("notifications.preferences.emailHint")}
            </Text>
            {GROUPS.map((group) => {
              const rows = group.types.flatMap((type) => byType.get(type) ?? []);
              if (rows.length === 0) return null;
              const groupName = t(`notifications.preferences.group.${group.key}`);
              return (
                <Stack key={group.key} gap="xs">
                  <Group justify="space-between" wrap="wrap" gap="xs">
                    <Title order={3} size="h4">
                      {groupName}
                    </Title>
                    <Group gap="md" wrap="wrap">
                      {CHANNELS.map((channel) => {
                        const column = t(channel.label);
                        const muted = channel.channel === "EMAIL" && !emailAvailable;
                        return (
                          <Group key={channel.channel} gap={4} wrap="nowrap">
                            <Text size="xs" c="dimmed">
                              {column}:
                            </Text>
                            <Button
                              variant="subtle"
                              size="compact-xs"
                              disabled={muted}
                              aria-label={t("notifications.preferences.allOnAria", { column, group: groupName })}
                              onClick={() => set(group.types, channel.channel, true)}
                            >
                              {t("notifications.preferences.allOn")}
                            </Button>
                            <Button
                              variant="subtle"
                              size="compact-xs"
                              disabled={muted}
                              aria-label={t("notifications.preferences.allOffAria", { column, group: groupName })}
                              onClick={() => set(group.types, channel.channel, false)}
                            >
                              {t("notifications.preferences.allOff")}
                            </Button>
                          </Group>
                        );
                      })}
                    </Group>
                  </Group>
                  <Table aria-label={groupName}>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>{t("notifications.preferences.typeColumn")}</Table.Th>
                        {CHANNELS.map((channel) => (
                          <Table.Th key={channel.channel}>{t(channel.label)}</Table.Th>
                        ))}
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {rows.map((kind) => {
                        const label = t(`notifications.preferences.kind.${kind.type}`);
                        return (
                          <Table.Tr key={kind.type}>
                            <Table.Td>{label}</Table.Td>
                            {CHANNELS.map((channel) => {
                              const column = t(channel.label);
                              return (
                                <Table.Td key={channel.channel}>
                                  <Switch
                                    aria-label={t("notifications.preferences.switchAria", { label, column })}
                                    checked={isOn(kind, channel)}
                                    disabled={channel.channel === "EMAIL" && !emailAvailable}
                                    onChange={(e) => set([kind.type], channel.channel, e.currentTarget.checked)}
                                  />
                                </Table.Td>
                              );
                            })}
                          </Table.Tr>
                        );
                      })}
                    </Table.Tbody>
                  </Table>
                </Stack>
              );
            })}
            {error && <FormErrorAlert>{error}</FormErrorAlert>}
            <Group justify="flex-end" gap="sm">
              <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!dirty}>
                {t("common.action.save")}
              </Button>
            </Group>
          </Stack>
        )}
      </Paper>
    </Stack>
  );
}
