import { Input } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { ValidationMessage } from "../utils/workflowForm";

/** A workflow validation message in Mantine's error style (a list-level or sub-list error with no input of its own to carry it). */
export default function ValidationError({ message }: { message?: ValidationMessage }) {
  const { t } = useTranslation();
  return message ? <Input.Error role="alert">{t(message.key, message.params)}</Input.Error> : null;
}
