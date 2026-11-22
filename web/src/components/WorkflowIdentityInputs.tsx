import { TextInput } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { SUPPORTED_LANGUAGES } from "../i18n";
import { MAX_KEY_LENGTH } from "../utils/slug";
import {
  PRIMARY_LANGUAGE,
  validationText,
  withKey,
  withLabel,
  type DraftRow,
  type RowErrors,
} from "../utils/workflowForm";
import classes from "./WorkflowIdentityInputs.module.css";

/**
 * The label input(s) and the key input every workflow row starts with (a status, a field, an option). One label column for a
 * room, one per language for a template; the key follows the first label while the row is new and its key untouched, and is
 * read-only once the row is persisted (keys are immutable slugs).
 */
export default function WorkflowIdentityInputs<T extends DraftRow>({
  row,
  labelColumns,
  errors,
  onChange,
}: {
  row: T;
  labelColumns: 1 | 2;
  errors?: RowErrors;
  onChange: (next: T) => void;
}) {
  const { t } = useTranslation();
  const languages = labelColumns === 1 ? [PRIMARY_LANGUAGE] : SUPPORTED_LANGUAGES;
  return (
    <div className={classes.inputs}>
      {languages.map((language) => (
        <TextInput
          key={language}
          label={labelColumns === 1 ? t("workflows.label") : t("workflows.labelIn", { language: language.toUpperCase() })}
          value={row.labels[language]}
          onChange={(event) => onChange(withLabel(row, language, event.currentTarget.value))}
          error={validationText(t, errors?.labels?.[language])}
        />
      ))}
      <TextInput
        label={t("workflows.key")}
        value={row.key}
        onChange={(event) => onChange(withKey(row, event.currentTarget.value))}
        maxLength={MAX_KEY_LENGTH}
        readOnly={row.persisted}
        description={row.persisted ? t("workflows.keyLocked") : undefined}
        error={validationText(t, errors?.key)}
        inputWrapperOrder={["label", "input", "description", "error"]}
      />
    </div>
  );
}
