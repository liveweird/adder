import { Fragment } from "react";
import { useTranslation } from "react-i18next";
import { Anchor, Text } from "@mantine/core";
import type { ThreadFieldValues, ThreadResponse } from "../api/threads";
import type { WorkflowField } from "../utils/threadForm";
import PersonLink from "./PersonLink";

/** An absolute http(s) address, or null: the server only stores these, but a link is never built from anything else. */
function webUrl(text: string): string | null {
  try {
    const url = new URL(text);
    return url.protocol === "http:" || url.protocol === "https:" ? url.href : null;
  } catch {
    return null;
  }
}

/**
 * One stored field value, read-only, as its type reads best: option LABELS for a SELECT and a MULTI_SELECT (a key the workflow no longer lists
 * is kept and shown as it is, with a "removed option" hint, until someone changes it), a USER's display name from the thread's
 * `fieldUserNames` (a link to their profile; an id the thread no longer names is the plain `#id`), a URL as a link that opens in a new tab, and "Not set" for an empty value.
 */
export default function ThreadFieldValue({
  field,
  value,
  userNames,
}: {
  field: Pick<WorkflowField, "type" | "options">;
  value: ThreadFieldValues[string] | undefined;
  userNames: ThreadResponse["fieldUserNames"];
}) {
  const { t } = useTranslation();
  if (value === undefined || value === null || (Array.isArray(value) && value.length === 0)) {
    return <Text span c="dimmed">{t("threads.details.notSet")}</Text>;
  }
  const optionLabel = (key: string) => {
    const option = field.options.find((candidate) => candidate.key === key);
    return option ? (
      option.label
    ) : (
      <>
        {key} <Text span c="dimmed" size="sm">{t("threads.field.removedOption")}</Text>
      </>
    );
  };
  if (Array.isArray(value)) {
    return (
      <>
        {value.map((key, index) => (
          <Fragment key={key}>
            {index > 0 && ", "}
            {optionLabel(key)}
          </Fragment>
        ))}
      </>
    );
  }
  if (field.type === "SELECT") return <>{optionLabel(String(value))}</>;
  if (field.type === "USER") {
    const name = userNames[String(value)];
    const id = Number(value);
    // A person the thread no longer names (`#id`) is no link: there is nobody to open.
    return name === undefined || !Number.isInteger(id) ? <>{`#${String(value)}`}</> : <PersonLink id={id} name={name} />;
  }
  const text = String(value);
  const href = field.type === "URL" ? webUrl(text) : null;
  return href === null ? (
    <>{text}</>
  ) : (
    <Anchor href={href} target="_blank" rel="noopener noreferrer">
      {text}
    </Anchor>
  );
}
