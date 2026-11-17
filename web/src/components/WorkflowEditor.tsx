import { useState } from "react";
import { Button, Group, Stack, Text, Title } from "@mantine/core";
import { IconPlus } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import {
  MAX_FIELDS,
  MAX_STATUSES,
  displayName,
  moveItem,
  newFieldDraft,
  newStatusDraft,
  removeStatus,
  replaceItem,
  replacementCandidates,
  replacementTarget,
  restoreStatus,
  setInitialStatus,
  setReplacement,
  removeItem,
  type StatusDraft,
  type WorkflowDraft,
  type WorkflowErrors,
} from "../utils/workflowForm";
import RetireStatusModal from "./RetireStatusModal";
import ValidationError from "./ValidationError";
import WorkflowFieldRow from "./WorkflowFieldRow";
import WorkflowStatusRow from "./WorkflowStatusRow";

/**
 * The workflow editor for BOTH documents: a room's workflow (`labelColumns` 1, one plain label) and a template's (2, English
 * and Polish). It is controlled — the page owns the draft, the baseline for the dirty check, the revision the draft was seeded
 * with and the save — and presentational otherwise: rows, reorder, the retire flow and its Undo notices.
 *
 * `conflictKeys` is the `keys` of a `409 status-in-use`: each removed status named there that has no live replacement (none
 * chosen, or the target was removed or re-categorised since) re-opens the retire dialog, one after another. Cancel skips a
 * status for as long as the keys keep the same CONTENT (compared by value, so a fresh array each render is harmless); a page
 * that wants to ask again after a failed save clears `conflictKeys` when it starts saving. `errors` come from `validateWorkflowDraft` and
 * are passed once the page wants them shown.
 */
export default function WorkflowEditor({
  draft,
  onChange,
  labelColumns,
  errors,
  conflictKeys,
}: {
  draft: WorkflowDraft;
  onChange: (draft: WorkflowDraft) => void;
  labelColumns: 1 | 2;
  errors?: WorkflowErrors;
  conflictKeys?: readonly string[];
}) {
  const { t } = useTranslation();
  const rows = errors?.rows ?? {};
  // The status whose Remove click is waiting for a replacement choice (it stays in the draft until then).
  const [removingId, setRemovingId] = useState<string | null>(null);
  // Conflict follow-ups the user dismissed, valid for the key set (by content) they were dismissed against.
  const conflictSignature = conflictKeys?.join("|");
  const [skipped, setSkipped] = useState<{ signature: string | undefined; ids: string[] }>({ signature: undefined, ids: [] });
  // A different key set (a page clearing the keys to ask again, a new conflict) forgets the dismissals.
  if (skipped.signature !== conflictSignature) setSkipped({ signature: conflictSignature, ids: [] });
  const skippedIds = skipped.ids;

  const removing = draft.statuses.find((s) => s.id === removingId);
  const conflicting = draft.retired.find(
    (r) => replacementTarget(draft, r) === undefined && (conflictKeys?.includes(r.status.key) ?? false) && !skippedIds.includes(r.status.id),
  );
  // The one status the dialog is about, with what confirming and cancelling mean for it.
  const ask: { status: StatusDraft; confirm: (replacementId: string) => void; cancel: () => void } | null = removing
    ? {
        status: removing,
        confirm: (key) => {
          onChange(removeStatus(draft, removing.id, key));
          setRemovingId(null);
        },
        cancel: () => setRemovingId(null),
      }
    : conflicting
      ? {
          status: conflicting.status,
          confirm: (key) => onChange(setReplacement(draft, conflicting.status.id, key)),
          cancel: () => setSkipped({ signature: conflictSignature, ids: [...skippedIds, conflicting.status.id] }),
        }
      : null;

  const requestRemove = (status: StatusDraft) => {
    if (status.persisted && status.threadCount > 0) setRemovingId(status.id);
    else onChange(removeStatus(draft, status.id, null));
  };

  return (
    <Stack gap="xl">
      <Stack gap="sm">
        <Title order={3} size="h4">
          {t("workflows.statuses.title")}
        </Title>
        <Text size="sm" c="dimmed">
          {t("workflows.statuses.hint")}
        </Text>
        <div role="radiogroup" aria-label={t("workflows.statuses.initial")} style={{ display: "contents" }}>
          {draft.statuses.map((status, index) => (
            <WorkflowStatusRow
              key={status.id}
              status={status}
              index={index}
              count={draft.statuses.length}
              labelColumns={labelColumns}
              errors={rows[status.id]}
              onChange={(next) => onChange({ ...draft, statuses: replaceItem(draft.statuses, next) })}
              onSetInitial={() => onChange({ ...draft, statuses: setInitialStatus(draft.statuses, status.id) })}
              onMove={(delta) => onChange({ ...draft, statuses: moveItem(draft.statuses, index, delta) })}
              onRemove={() => requestRemove(status)}
            />
          ))}
        </div>
        <ValidationError message={errors?.statuses} />
        <ValidationError message={errors?.initial} />
        <ValidationError message={errors?.closed} />
        {draft.retired.length > 0 && (
          <Stack gap={4} role="group" aria-label={t("workflows.retired.title")}>
            {draft.retired.map((retired) => {
              const { status } = retired;
              const target = replacementTarget(draft, retired);
              const values = { status: displayName(status), count: status.threadCount, target: target ? displayName(target) : "" };
              return (
                <Group key={status.id} justify="space-between" wrap="nowrap">
                  <Text size="sm">{target ? t("workflows.retired.lineMoved", values) : t("workflows.retired.line", values)}</Text>
                  <Button
                    size="compact-xs"
                    variant="subtle"
                    aria-label={t("workflows.retired.undoAria", values)}
                    onClick={() => onChange(restoreStatus(draft, status.id))}
                  >
                    {t("workflows.retired.undo")}
                  </Button>
                </Group>
              );
            })}
          </Stack>
        )}
        <Group>
          <Button
            variant="default"
            size="xs"
            leftSection={<IconPlus size={14} />}
            disabled={draft.statuses.length >= MAX_STATUSES}
            onClick={() => onChange({ ...draft, statuses: [...draft.statuses, newStatusDraft()] })}
          >
            {t("workflows.statuses.add")}
          </Button>
        </Group>
      </Stack>

      <Stack gap="sm">
        <Title order={3} size="h4">
          {t("workflows.fields.title")}
        </Title>
        <Text size="sm" c="dimmed">
          {t("workflows.fields.hint")}
        </Text>
        {draft.fields.map((field, index) => (
          <WorkflowFieldRow
            key={field.id}
            field={field}
            index={index}
            count={draft.fields.length}
            labelColumns={labelColumns}
            rowErrors={rows}
            onChange={(next) => onChange({ ...draft, fields: replaceItem(draft.fields, next) })}
            onMove={(delta) => onChange({ ...draft, fields: moveItem(draft.fields, index, delta) })}
            onRemove={() => onChange({ ...draft, fields: removeItem(draft.fields, field.id) })}
          />
        ))}
        {draft.fields.length === 0 && (
          <Text size="sm" c="dimmed">
            {t("workflows.fields.empty")}
          </Text>
        )}
        <ValidationError message={errors?.fields} />
        <Group>
          <Button
            variant="default"
            size="xs"
            leftSection={<IconPlus size={14} />}
            disabled={draft.fields.length >= MAX_FIELDS}
            onClick={() => onChange({ ...draft, fields: [...draft.fields, newFieldDraft()] })}
          >
            {t("workflows.fields.add")}
          </Button>
        </Group>
      </Stack>

      <RetireStatusModal
        status={ask?.status ?? null}
        candidates={ask ? replacementCandidates(draft, ask.status) : []}
        onConfirm={(key) => ask?.confirm(key)}
        onCancel={() => ask?.cancel()}
      />
    </Stack>
  );
}
