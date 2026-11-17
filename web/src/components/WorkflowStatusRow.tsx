import { Group, Paper, Radio, SegmentedControl, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { RowErrors, StatusCategory, StatusDraft } from "../utils/workflowForm";
import RowControls from "./RowControls";
import StatusColourSelect from "./StatusColourSelect";
import WorkflowIdentityInputs from "./WorkflowIdentityInputs";

/**
 * One status of a workflow: label(s), key, colour, an Open/Closed category (locked while threads use the status), the radio
 * that makes it THE initial status (OPEN rows only) and the up/down/remove controls. The row is controlled: every edit
 * arrives as a whole next draft, and removal is the editor's call (a status with threads asks for a replacement first).
 */
export default function WorkflowStatusRow({
  status,
  index,
  count,
  labelColumns,
  errors,
  onChange,
  onSetInitial,
  onMove,
  onRemove,
}: {
  status: StatusDraft;
  index: number;
  count: number;
  labelColumns: 1 | 2;
  errors?: RowErrors;
  onChange: (next: StatusDraft) => void;
  onSetInitial: () => void;
  onMove: (delta: -1 | 1) => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const position = index + 1;
  const locked = status.threadCount > 0;
  return (
    <Paper withBorder p="sm" role="group" aria-label={t("workflows.statuses.rowAria", { position })}>
      <Group align="flex-start" gap="md" wrap="wrap">
        <WorkflowIdentityInputs row={status} labelColumns={labelColumns} errors={errors} onChange={onChange} />
        <StatusColourSelect value={status.colour} onChange={(colour) => onChange({ ...status, colour })} />
        <Stack gap={4}>
          <Text size="sm" fw={500} id={`${status.id}-category`}>
            {t("workflows.statuses.category")}
          </Text>
          <SegmentedControl
            aria-labelledby={`${status.id}-category`}
            value={status.category}
            onChange={(category) => onChange({ ...status, category: category as StatusCategory })}
            disabled={locked}
            data={[
              { value: "OPEN", label: t("workflows.statuses.categoryOpen") },
              { value: "CLOSED", label: t("workflows.statuses.categoryClosed") },
            ]}
          />
          {locked && (
            <Text size="xs" c="dimmed">
              {t("workflows.statuses.categoryLocked")}
            </Text>
          )}
        </Stack>
        <Radio
          name="workflow-initial-status"
          label={t("workflows.statuses.initial")}
          checked={status.initial}
          onChange={onSetInitial}
          disabled={status.category !== "OPEN"}
          mt={26}
        />
        <RowControls
          index={index}
          count={count}
          onMoveUp={() => onMove(-1)}
          onMoveDown={() => onMove(1)}
          onRemove={onRemove}
          moveUpLabel={t("workflows.statuses.moveUpAria", { position })}
          moveDownLabel={t("workflows.statuses.moveDownAria", { position })}
          removeLabel={t("workflows.statuses.removeAria", { position })}
        />
      </Group>
    </Paper>
  );
}
