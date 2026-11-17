import type { MantineColor } from "@mantine/core";

/**
 * The semantic colour vocabulary a workflow status may carry (the server's `StatusColour`, UPPER_CASE on the wire). It IS the
 * app-wide palette (web/CLAUDE.md): gray neutral, red blocking, orange warning, blue in progress, grape waiting, teal success —
 * never green or lime, which sit beside the olive brand. The order is the picker's order.
 */
export const STATUS_COLOURS = ["GRAY", "BLUE", "GRAPE", "TEAL", "ORANGE", "RED"] as const;

export type StatusColour = (typeof STATUS_COLOURS)[number];

/** The Mantine hue for a wire colour — one lowercase, never shade-suffixed (the AA ink comes from `themeVariables.ts`). */
export function statusHue(colour: StatusColour): MantineColor {
  return colour.toLowerCase();
}

/** The i18n keys of a colour's name ("Blue") and meaning ("in progress"); the colour is never the only carrier. */
export function statusColourKeys(colour: StatusColour): {
  name: `workflows.colour.${StatusColour}.name`;
  meaning: `workflows.colour.${StatusColour}.meaning`;
} {
  return { name: `workflows.colour.${colour}.name`, meaning: `workflows.colour.${colour}.meaning` };
}
