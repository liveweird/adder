import type { ReactNode } from "react";
import { MultiSelect, NumberInput, Select, TextInput } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { MAX_TEXT_FIELD_LENGTH, MAX_URL_FIELD_LENGTH, type FieldDraft, type WorkflowField } from "../utils/threadForm";
import RoomMemberSelect from "./RoomMemberSelect";

type Option = { value: string; label: string; disabled?: boolean };

/**
 * One workflow field's input, by type (the shape of Toadie's `EntityPropertyField`): TEXT and URL a text box, NUMBER a number box,
 * DATE a native date box (`YYYY-MM-DD`, the wire format), SELECT a Select over the field's options, MULTI_SELECT a MultiSelect and USER
 * a picker over the room's members. The draft is whole (`onChange` receives the next one); an optional field can be cleared, a
 * required one is marked and refuses to be left empty at submit (the form's rules).
 */
export default function ThreadFieldInput({
  roomId,
  field,
  draft,
  onChange,
  error,
  disabled,
  readOnly,
}: {
  roomId: number;
  field: WorkflowField;
  draft: FieldDraft;
  onChange: (next: FieldDraft) => void;
  error?: ReactNode;
  disabled?: boolean;
  /** Locked without losing the caret (a save in flight). */
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const common = { label: field.label, required: field.required, error, disabled, readOnly };
  const options: Option[] = field.options.map((option) => ({ value: option.key, label: option.label }));
  // A held key the workflow no longer lists (an option removed after it was chosen) stays shown, marked, and is not offered as a new choice.
  const held = draft.kind === "multi" ? draft.value : draft.kind === "text" && field.type === "SELECT" && draft.value !== "" ? [draft.value] : [];
  for (const key of held) {
    if (!options.some((option) => option.value === key)) options.push({ value: key, label: `${key} ${t("threads.field.removedOption")}`, disabled: true });
  }

  if (draft.kind === "user") {
    return <RoomMemberSelect roomId={roomId} {...common} picked={draft.value} onPick={(picked) => onChange({ kind: "user", value: picked })} />;
  }
  if (draft.kind === "multi") {
    return (
      <MultiSelect
        {...common}
        data={options}
        value={draft.value}
        onChange={(value) => onChange({ kind: "multi", value })}
        placeholder={t("threads.field.pick")}
        clearable
        clearButtonProps={{ "aria-label": t("threads.field.clear", { label: field.label }) }}
      />
    );
  }
  const set = (value: string) => onChange({ kind: "text", value });
  switch (field.type) {
    case "SELECT":
      return (
        <Select
          {...common}
          data={options}
          value={draft.value === "" ? null : draft.value}
          onChange={(value) => set(value ?? "")}
          placeholder={t("threads.field.pick")}
          clearable={!field.required}
          clearButtonProps={{ "aria-label": t("threads.field.clear", { label: field.label }) }}
        />
      );
    case "NUMBER":
      // A string draft: a half-typed "1." or "-" must survive until the form's number rule judges it.
      return <NumberInput {...common} value={draft.value} onChange={(value) => set(String(value))} hideControls clampBehavior="none" allowNegative />;
    case "DATE":
      return <TextInput {...common} type="date" value={draft.value} onChange={(event) => set(event.currentTarget.value)} />;
    case "URL":
      return <TextInput {...common} type="url" inputMode="url" placeholder="https://" maxLength={MAX_URL_FIELD_LENGTH} value={draft.value} onChange={(event) => set(event.currentTarget.value)} />;
    default:
      return <TextInput {...common} maxLength={MAX_TEXT_FIELD_LENGTH} value={draft.value} onChange={(event) => set(event.currentTarget.value)} />;
  }
}
