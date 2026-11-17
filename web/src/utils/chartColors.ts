/**
 * Chart colours (telemetry trend charts today).
 *
 * The categorical palette: the colour of the Nth line of a chart whose series are arbitrary NAMES (a process role, a database
 * schema). Such a name must not wear a colour that MEANS something (teal = success, orange = warning, red = blocking), so those
 * three hues are excluded, and so are the stock `green`, `lime` and `yellow` (the brand's olive sits between them: a line in one of them would read as the brand). Each entry is the ONE shade of its hue
 * that clears 3:1 on all four chart surfaces, so there is no per-scheme pair. Series are coloured by position
 * (`palette[index % length]`), and the order maximises the CIEDE2000 distance between NEIGHBOURS (dataviz floor ≥ 15).
 * `chartColors.test.ts` recomputes every ratio and distance from the theme (white / canvas | dark paper / dark canvas | ΔE to the
 * next entry):
 *
 *   adder.6      3.25 / 3.02 | 4.18 / 5.07   ΔE 76.8
 *   violet.5     4.26 / 3.96 | 3.18 / 3.87   ΔE 29.4
 *   pink.6       3.73 / 3.46 | 3.64 / 4.42   ΔE 35.4
 *   indigo.5     3.67 / 3.41 | 3.70 / 4.49   ΔE 17.5
 *   gray.6       3.32 / 3.08 | 4.09 / 4.96   ΔE 32.5 (to the first entry again)
 *
 * Five is all the passing hues give, so from the 6th series on the first colour repeats: a chart on this palette still carries
 * identity without colour through the legend and the table beside the chart.
 */
export const CATEGORY_PALETTE: ReadonlyArray<string> = ["adder.6", "violet.5", "pink.6", "indigo.5", "gray.6"];
