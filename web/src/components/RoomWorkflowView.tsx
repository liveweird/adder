import { Group, Stack, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { statusHue } from "../utils/workflowColours";
import { displayName, takesOptions, type WorkflowDraft } from "../utils/workflowForm";
import StatusPill from "./StatusPill";

/**
 * The room's workflow as plain text, for everyone who cannot edit it (a plain member, anyone while the room is archived): the
 * statuses and fields as lists under the editor's own headings. Plain text rather than disabled controls, so a keyboard and a
 * screen reader reach every value.
 */
export default function RoomWorkflowView({ draft }: { draft: WorkflowDraft }) {
  const { t } = useTranslation();
  return (
    <Stack gap="xl">
      <Stack gap="sm">
        <Title order={3} size="h4">
          {t("workflows.statuses.title")}
        </Title>
        <Stack component="ul" gap="xs" m={0} p={0} style={{ listStyle: "none" }}>
          {draft.statuses.map((status) => (
            <li key={status.id}>
              <Group gap="sm">
                <StatusPill color={statusHue(status.colour)} dot>
                  {displayName(status)}
                </StatusPill>
                <Text size="sm" c="dimmed">
                  {[t(status.category === "OPEN" ? "workflows.statuses.categoryOpen" : "workflows.statuses.categoryClosed"), status.initial ? t("workflows.statuses.initial") : null]
                    .filter(Boolean)
                    .join(" · ")}
                </Text>
              </Group>
            </li>
          ))}
        </Stack>
      </Stack>
      <Stack gap="sm">
        <Title order={3} size="h4">
          {t("workflows.fields.title")}
        </Title>
        {draft.fields.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t("workflows.fields.empty")}
          </Text>
        ) : (
          <Stack component="ul" gap="xs" m={0} p={0} style={{ listStyle: "none" }}>
            {draft.fields.map((field) => (
              <li key={field.id}>
                <Text size="sm" fw={500} span>
                  {displayName(field)}
                </Text>
                <Text size="sm" c="dimmed" span>
                  {` — ${[t(`workflows.fieldType.${field.type}`), field.required ? t("workflows.fields.required") : null].filter(Boolean).join(", ")}`}
                </Text>
                {takesOptions(field.type) && (
                  <Text size="sm" c="dimmed">
                    {t("rooms.workflow.view.options", { options: field.options.map(displayName).join(", ") })}
                  </Text>
                )}
              </li>
            ))}
          </Stack>
        )}
      </Stack>
    </Stack>
  );
}
