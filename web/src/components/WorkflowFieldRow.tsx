import { Button, Group, Paper, Select, Stack, Switch, Text } from "@mantine/core";
import { IconPlus } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import {
  FIELD_TYPES,
  MAX_OPTIONS,
  moveItem,
  newOptionDraft,
  removeItem,
  replaceItem,
  takesOptions,
  type FieldDraft,
  type FieldType,
  type DraftRow,
  type WorkflowErrors,
} from "../utils/workflowForm";
import RowControls from "./RowControls";
import ValidationError from "./ValidationError";
import WorkflowIdentityInputs from "./WorkflowIdentityInputs";
import classes from "./WorkflowFieldRow.module.css";

/**
 * One custom field of a workflow: label(s), key, a type (fixed once the field is persisted), a Required switch and, for the
 * select kinds, the options sub-list with its own add/reorder/remove. Controlled like `WorkflowStatusRow`; `rowErrors` is the
 * whole error map (the field's and its options' rows share one id space).
 */
export default function WorkflowFieldRow({
  field,
  index,
  count,
  labelColumns,
  rowErrors,
  onChange,
  onMove,
  onRemove,
}: {
  field: FieldDraft;
  index: number;
  count: number;
  labelColumns: 1 | 2;
  rowErrors: WorkflowErrors["rows"];
  onChange: (next: FieldDraft) => void;
  onMove: (delta: -1 | 1) => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const position = index + 1;
  const setOptions = (options: DraftRow[]) => onChange({ ...field, options });
  return (
    <Paper withBorder p="md" role="group" aria-label={t("workflows.fields.rowAria", { position })} className={classes.row}>
      <Stack gap="sm">
        <div className={classes.header}>
          <Text size="xs" fw={650} c="dimmed">
            {t("workflows.fields.rowAria", { position })}
          </Text>
          <RowControls
            index={index}
            count={count}
            onMoveUp={() => onMove(-1)}
            onMoveDown={() => onMove(1)}
            onRemove={onRemove}
            moveUpLabel={t("workflows.fields.moveUpAria", { position })}
            moveDownLabel={t("workflows.fields.moveDownAria", { position })}
            removeLabel={t("workflows.fields.removeAria", { position })}
          />
        </div>
        <div className={classes.grid}>
          <div className={classes.identity}>
            <WorkflowIdentityInputs row={field} labelColumns={labelColumns} errors={rowErrors[field.id]} onChange={onChange} />
          </div>
          <Select
            label={t("workflows.fields.type")}
            data={FIELD_TYPES.map((type) => ({ value: type, label: t(`workflows.fieldType.${type}`) }))}
            value={field.type}
            allowDeselect={false}
            onChange={(type) => onChange({ ...field, type: type as FieldType })}
            disabled={field.persisted}
            description={field.persisted ? t("workflows.fields.typeLocked") : undefined}
          />
          <div className={classes.required}>
            <Switch
              label={t("workflows.fields.required")}
              checked={field.required}
              onChange={(event) => onChange({ ...field, required: event.currentTarget.checked })}
            />
          </div>
        </div>
        {takesOptions(field.type) && (
          <Stack gap="sm" role="group" aria-label={t("workflows.options.title")} className={classes.options}>
            <Text size="sm" fw={650}>
              {t("workflows.options.title")}
            </Text>
            {field.options.map((option, optionIndex) => (
              <div key={option.id} className={classes.optionRow}>
                <div className={classes.identity}>
                  <WorkflowIdentityInputs
                    row={option}
                    labelColumns={labelColumns}
                    errors={rowErrors[option.id]}
                    onChange={(next) => setOptions(replaceItem(field.options, next))}
                  />
                </div>
                <RowControls
                  index={optionIndex}
                  count={field.options.length}
                  onMoveUp={() => setOptions(moveItem(field.options, optionIndex, -1))}
                  onMoveDown={() => setOptions(moveItem(field.options, optionIndex, 1))}
                  onRemove={() => setOptions(removeItem(field.options, option.id))}
                  moveUpLabel={t("workflows.options.moveUpAria", { position: optionIndex + 1 })}
                  moveDownLabel={t("workflows.options.moveDownAria", { position: optionIndex + 1 })}
                  removeLabel={t("workflows.options.removeAria", { position: optionIndex + 1 })}
                />
              </div>
            ))}
            <ValidationError message={rowErrors[field.id]?.options} />
            <Group>
              <Button
                variant="default"
                size="xs"
                leftSection={<IconPlus size={14} />}
                disabled={field.options.length >= MAX_OPTIONS}
                onClick={() => setOptions([...field.options, newOptionDraft()])}
              >
                {t("workflows.options.add")}
              </Button>
            </Group>
          </Stack>
        )}
      </Stack>
    </Paper>
  );
}
