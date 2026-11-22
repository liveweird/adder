import { useId } from "react";
import { Badge, Group, Stack, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { statusHue } from "../utils/workflowColours";
import { displayName, takesOptions, type WorkflowDraft } from "../utils/workflowForm";
import StatusPill from "./StatusPill";
import classes from "./RoomWorkflowView.module.css";

/**
 * The room's workflow as plain text, for everyone who cannot edit it (a plain member, anyone while the room is archived): the
 * statuses and fields as lists under the editor's own headings. Plain text rather than disabled controls, so a keyboard and a
 * screen reader reach every value.
 */
export default function RoomWorkflowView({ draft }: { draft: WorkflowDraft }) {
  const { t } = useTranslation();
  const statusesTitleId = useId();
  const fieldsTitleId = useId();
  return (
    <Stack gap="xl" className={classes.view}>
      <section aria-labelledby={statusesTitleId}>
        <Title order={4} size="h4" id={statusesTitleId} mb="sm">
          {t("workflows.statuses.title")}
        </Title>
        <Stack component="ol" gap="xs" m={0} p={0} className={classes.statusList}>
          {draft.statuses.map((status) => (
            <li key={status.id} className={classes.statusItem}>
              <Group gap="sm" justify="space-between" wrap="wrap">
                <StatusPill color={statusHue(status.colour)} dot wrap>
                  {displayName(status)}
                </StatusPill>
                <Group gap="xs" wrap="wrap">
                  <Text size="sm" c="dimmed">
                    {[t(status.category === "OPEN" ? "workflows.statuses.categoryOpen" : "workflows.statuses.categoryClosed"), status.initial ? t("workflows.statuses.initial") : null]
                      .filter(Boolean)
                      .join(" · ")}
                  </Text>
                </Group>
              </Group>
            </li>
          ))}
        </Stack>
      </section>
      <section aria-labelledby={fieldsTitleId}>
        <Title order={4} size="h4" id={fieldsTitleId} mb="sm">
          {t("workflows.fields.title")}
        </Title>
        {draft.fields.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t("workflows.fields.empty")}
          </Text>
        ) : (
          <Stack component="ul" gap="sm" m={0} p={0} className={classes.fieldList}>
            {draft.fields.map((field) => (
              <li key={field.id} className={classes.fieldItem}>
                <Group gap="xs" justify="space-between" align="flex-start" wrap="wrap">
                  <Text size="sm" fw={650} className={classes.name}>
                    {displayName(field)}
                  </Text>
                  <Group gap={6} wrap="wrap">
                    <Badge color="gray" variant="light">
                      {t(`workflows.fieldType.${field.type}`)}
                    </Badge>
                    {field.required && (
                      <Badge color="gray" variant="outline">
                        {t("workflows.fields.required")}
                      </Badge>
                    )}
                  </Group>
                </Group>
                {takesOptions(field.type) && (
                  <Text size="sm" c="dimmed" className={classes.options}>
                    {t("rooms.workflow.view.options", { options: field.options.map(displayName).join(", ") })}
                  </Text>
                )}
              </li>
            ))}
          </Stack>
        )}
      </section>
    </Stack>
  );
}
