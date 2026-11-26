import type { TFunction } from "i18next";
import type { TelemetryProcess } from "../api/telemetry";

/**
 * Visible and accessible names for process boots. A restarted pod may retain its instance name, so the newest boot keeps the
 * familiar short label while every older boot gets a stable process-id qualifier. `startedAt`, then id, makes the choice
 * independent of API row order and unambiguous when two starts share a timestamp.
 */
export function telemetryProcessLabels(processes: readonly TelemetryProcess[], t: TFunction): ReadonlyMap<number, string> {
  const byInstance = new Map<string, TelemetryProcess[]>();
  for (const process of processes) {
    const matches = byInstance.get(process.instance) ?? [];
    matches.push(process);
    byInstance.set(process.instance, matches);
  }

  const labels = new Map<number, string>();
  for (const [instance, matches] of byInstance) {
    matches.sort((left, right) => right.startedAt - left.startedAt || right.id - left.id);
    for (const [index, process] of matches.entries()) {
      labels.set(
        process.id,
        index === 0 ? instance : t("telemetry.process.earlierBoot", { instance, id: process.id }),
      );
    }
  }
  return labels;
}
