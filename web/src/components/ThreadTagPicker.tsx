import { MultiSelect, type MultiSelectProps } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { TagCategory } from "../api/tagCategories";
import { MAX_THREAD_TAGS, tagOptionGroups } from "../utils/threadTags";
import { accessibleRenderPill } from "./accessiblePill";

/** The shared, registry-backed tag control used while starting and editing a thread. */
export default function ThreadTagPicker({
  categories,
  value,
  onChange,
  ...props
}: {
  categories: readonly TagCategory[];
  value: string[];
  onChange: (value: string[]) => void;
} & Omit<MultiSelectProps, "data" | "value" | "onChange" | "maxValues" | "renderPill">) {
  const { t } = useTranslation();

  return (
    <MultiSelect
      label={t("threads.tags.label")}
      description={t("threads.tags.hint")}
      placeholder={value.length === 0 ? t("threads.tags.placeholder") : undefined}
      data={tagOptionGroups(categories)}
      value={value}
      onChange={onChange}
      maxValues={MAX_THREAD_TAGS}
      renderPill={accessibleRenderPill((label) => t("common.filter.removeValue", { value: label }))}
      searchable
      clearable
      clearButtonProps={{ "aria-label": t("threads.tags.clear") }}
      nothingFoundMessage={t("threads.tags.empty")}
      {...props}
    />
  );
}
