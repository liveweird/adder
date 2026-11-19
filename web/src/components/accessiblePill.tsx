import { Pill, type MultiSelectProps } from "@mantine/core";

/**
 * A `renderPill` for MultiSelect whose remove button is a real, focusable, named control (Lettuce's, ported).
 * Mantine hides the default remove button from the a11y tree (aria-hidden + tabindex -1),
 * leaving keyboard/screen-reader users only the undiscoverable Backspace gesture — every
 * multi-value filter renders its pills through this instead. `removeLabel` words the per-pill
 * aria-label from the option label.
 */
export function accessibleRenderPill(
  removeLabel: (optionLabel: string) => string,
): NonNullable<MultiSelectProps["renderPill"]> {
  // Mantine 9.8 calls renderPill with `option: undefined` for a selected value absent from `data` (a stored pick whose options load
  // late or were removed) although its type says otherwise; fall back to the raw value.
  return ({ option, value, onRemove, disabled }) => {
    const label = (option as { label: string } | undefined)?.label ?? String(value ?? "");
    return (
      <Pill
        withRemoveButton={!disabled}
        onRemove={onRemove}
        removeButtonProps={{
          "aria-label": removeLabel(label),
          "aria-hidden": false,
          tabIndex: 0,
        }}
      >
        {label}
      </Pill>
    );
  };
}
