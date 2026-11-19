import { MultiSelect, type ComboboxData, type MultiSelectProps } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { accessibleRenderPill } from "./accessiblePill";

/**
 * The multi-value list filter (Lettuce's, ported; API-LIST-004 "any of"): a searchable, clearable MultiSelect whose empty state reads
 * "Any" (no filter), with named pill remove buttons. The caller holds the array; a list filter sends it as a repeated key. Search folds
 * accents through the theme-level `foldedOptionsFilter`. Typed on the option value so a status filter's setter takes its own array type.
 */
export default function FilterMultiSelect<T extends string = string>({
  value,
  onChange,
  data,
  emptyLabel,
  valueLabel,
  w = 220,
  ...rest
}: Omit<MultiSelectProps, "value" | "onChange" | "data" | "renderPill" | "searchable" | "clearable"> & {
  value: readonly T[];
  onChange: (next: T[]) => void;
  data: ComboboxData;
  /** What an empty selection reads (no filter); defaults to "Any". A caller whose labels are gendered or plural words passes its own. */
  emptyLabel?: string;
  /** The label of a selected value that has no option in `data` (a stored pick whose options load late); defaults to the raw value. */
  valueLabel?: (value: string) => string;
}) {
  const { t } = useTranslation();
  return (
    <MultiSelect
      placeholder={value.length === 0 ? (emptyLabel ?? t("common.state.any")) : undefined}
      renderPill={accessibleRenderPill((label) => t("common.filter.removeValue", { value: label }), valueLabel)}
      searchable
      clearable
      w={w}
      {...rest}
      data={data}
      value={[...value]}
      onChange={(next) => onChange(next as T[])}
    />
  );
}
