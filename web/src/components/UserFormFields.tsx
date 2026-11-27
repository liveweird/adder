import { Checkbox, Select, SimpleGrid, TextInput } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import { NATIVE_LANGUAGE_NAMES, SUPPORTED_LANGUAGES } from "../i18n";
import { MAX_EMAIL_LENGTH, MAX_USER_NAME_LENGTH, type UserFormValues } from "../utils/userForm";
import classes from "./ManagementSurface.module.css";

/** The field block shared by the create and edit user pages (which own submit/error handling). */
export default function UserFormFields({ form }: { form: UseFormReturnType<UserFormValues> }) {
  const { t } = useTranslation();
  return (
    <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md" verticalSpacing="sm" className={classes.identityGrid}>
      <TextInput
        label={t("common.field.name")}
        autoFocus
        required
        maxLength={MAX_USER_NAME_LENGTH}
        {...form.getInputProps("name")}
      />
      <TextInput
        label={t("common.field.email")}
        required
        maxLength={MAX_EMAIL_LENGTH}
        {...form.getInputProps("email")}
      />
      <Select
        label={t("common.language.label")}
        description={t("users.languageHint")}
        data={SUPPORTED_LANGUAGES.map((lng) => ({ value: lng, label: NATIVE_LANGUAGE_NAMES[lng] }))}
        allowDeselect={false}
        {...form.getInputProps("language")}
      />
      <Checkbox
        className={classes.permissionField}
        label={t("users.adminCheckbox")}
        description={t("users.adminCheckboxHint")}
        {...form.getInputProps("admin", { type: "checkbox" })}
      />
    </SimpleGrid>
  );
}
