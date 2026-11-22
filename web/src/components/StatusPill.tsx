import type { CSSProperties, ReactNode } from "react";
import { Badge, type MantineColor } from "@mantine/core";
import classes from "./StatusPill.module.css";

// The hue dot's colour: the mid shade in light mode, a lighter one on the dark tint (never a shade-suffixed colour).
function hueDotVar(color: MantineColor): CSSProperties {
  const hue = color.split(".")[0];
  return { "--pill-dot": `light-dark(var(--mantine-color-${hue}-6), var(--mantine-color-${hue}-4))` } as CSSProperties;
}

/**
 * The one status/label pill (Lettuce's, ported): a sentence-case light Badge in the hue's AA ink (`themeVariables.ts`),
 * optionally with a hue dot, plus the min-width guard that keeps Mantine from ellipsizing it inside table cells. A
 * workflow status renders through it with the hue from `utils/workflowColours.ts` — the one colour source.
 */
export default function StatusPill({
  color,
  children,
  dot = false,
  size = "md",
  ariaLabel,
  wrap = false,
}: {
  /** A bare hue name ("teal", "gray") — never a shade-suffixed colour: the tint comes from the hue, the ink from the theme. */
  color: MantineColor;
  children: ReactNode;
  /** A 6px hue dot before the text: the colour is then never the only carrier next to the label. */
  dot?: boolean;
  size?: "sm" | "md";
  ariaLabel?: string;
  wrap?: boolean;
}) {
  return (
    <Badge
      color={color}
      size={size}
      leftSection={dot ? <span className={classes.dot} aria-hidden="true" /> : undefined}
      classNames={wrap ? { root: classes.wrap, label: classes.wrapLabel } : undefined}
      style={{ minWidth: wrap ? 0 : "max-content", ...hueDotVar(color) }}
      aria-label={ariaLabel}
    >
      {children}
    </Badge>
  );
}
