import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Paper, Stack, Switch, Text, Title } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getNotificationPreferences, setNotificationPreferences, type NotificationType } from "../api/notifications";
import { getUserId } from "../api/session";
import ErrorAlert from "../components/ErrorAlert";
import FormErrorAlert from "../components/FormErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * The caller's own notification-email preferences: one switch per kind of notice (the kinds come from the server), checked = the kind is in the
 * email digest. Save is a wholesale replace of the SWITCHED-OFF pairs (`PUT /users/{id}/notification-preferences`); in-app notices cannot be
 * switched off. A deployment with no outbound email says so and its switches are disabled.
 */
export default function NotificationPreferences() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const userId = getUserId();
  // The unsaved toggles; the effective state derives from the loaded set + these, so no effect-driven seeding is needed.
  const [overrides, setOverrides] = useState<Partial<Record<NotificationType, boolean>>>({});
  const [error, setError] = useState<string | null>(null);

  const query = useQuery({
    queryKey: ["notificationPreferences", userId],
    queryFn: () => getNotificationPreferences(userId as number),
    enabled: userId !== null,
  });
  const kinds = query.data?.kinds ?? [];
  const isOn = (type: NotificationType, stored: boolean) => overrides[type] ?? stored;
  const dirty = kinds.some((k) => isOn(k.type, k.email) !== k.email);

  const save = useMutation({
    mutationFn: () =>
      setNotificationPreferences(
        userId as number,
        kinds.filter((k) => !isOn(k.type, k.email)).map((k) => ({ type: k.type, channel: "EMAIL" as const })),
      ),
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

  return (
    <Stack gap="md">
      <PageHeader title={t("notifications.preferences.title")} description={t("notifications.preferences.description")} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        {query.isLoading ? (
          <LoadingBlock />
        ) : query.isError || query.data === undefined ? (
          <ErrorAlert error={query.error} />
        ) : (
          <Stack>
            <Title order={3} size="h4">
              {t("notifications.preferences.section")}
            </Title>
            {!query.data.emailAvailable && (
              <Alert color="gray" variant="light">
                {t("notifications.preferences.emailOff")}
              </Alert>
            )}
            <Stack role="group" aria-label={t("notifications.preferences.section")}>
              {kinds.map((k) => (
                <Switch
                  key={k.type}
                  label={t(`notifications.preferences.kind.${k.type}`)}
                  checked={isOn(k.type, k.email)}
                  disabled={!query.data.emailAvailable}
                  onChange={(e) => {
                    const value = e.currentTarget.checked;
                    setOverrides((prev) => ({ ...prev, [k.type]: value }));
                  }}
                />
              ))}
            </Stack>
            <Text c="dimmed" size="sm">
              {t("notifications.preferences.inAppHint")}
            </Text>
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
