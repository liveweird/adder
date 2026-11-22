import type { ReactNode } from "react";
import { ActionIcon, Tooltip } from "@mantine/core";

/**
 * An icon-only action in a compact header (the thread pane's focus, pin, watch and details controls): an `ActionIcon` in a tooltip whose
 * words are also its accessible name, so a by-name locator finds it exactly as it found the labelled button it replaced. A toggle passes
 * `pressed` (`aria-pressed`) or, for a control that folds something away, `expanded` (+ `controls`, `aria-expanded`); the lit variant
 * follows whichever is given. The icon is decorative: the label carries the meaning.
 */
export default function HeaderIconButton({
  label,
  icon,
  pressed,
  expanded,
  controls,
  loading,
  onClick,
}: {
  label: string;
  icon: ReactNode;
  pressed?: boolean;
  expanded?: boolean;
  controls?: string;
  loading?: boolean;
  onClick: () => void;
}) {
  const lit = pressed ?? expanded ?? false;
  return (
    <Tooltip label={label}>
      <ActionIcon
        variant={lit ? "light" : "subtle"}
        aria-label={label}
        aria-pressed={pressed}
        aria-expanded={expanded}
        aria-controls={controls}
        loading={loading}
        onClick={onClick}
      >
        {icon}
      </ActionIcon>
    </Tooltip>
  );
}
