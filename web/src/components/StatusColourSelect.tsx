import { Group, Select, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { STATUS_COLOURS, statusColourKeys, statusHue, type StatusColour } from "../utils/workflowColours";
import StatusPill from "./StatusPill";

/**
 * A `Select` over exactly the six status colours. Each option is a `StatusPill` preview plus the colour's name and its meaning
 * ("Blue — in progress"), and the input reads the same words, so the colour is never the only carrier.
 */
export default function StatusColourSelect({
  value,
  onChange,
  label,
  disabled,
}: {
  value: StatusColour;
  onChange: (colour: StatusColour) => void;
  /** Replaces the default "Colour" label. */
  label?: string;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const data = STATUS_COLOURS.map((colour) => {
    const keys = statusColourKeys(colour);
    return { value: colour, label: t("workflows.colour.option", { name: t(keys.name), meaning: t(keys.meaning) }) };
  });
  return (
    <Select
      label={label ?? t("workflows.colour.label")}
      data={data}
      value={value}
      // The six values are the whole vocabulary: the data never holds a "none" and the control cannot be cleared.
      allowDeselect={false}
      onChange={(next) => onChange(next as StatusColour)}
      disabled={disabled}
      renderOption={({ option }) => {
        const keys = statusColourKeys(option.value as StatusColour);
        return (
          <Group gap="xs" wrap="nowrap">
            <StatusPill color={statusHue(option.value as StatusColour)} dot size="sm">
              {t(keys.name)}
            </StatusPill>
            <Text size="sm" c="dimmed">
              {t(keys.meaning)}
            </Text>
          </Group>
        );
      }}
    />
  );
}
