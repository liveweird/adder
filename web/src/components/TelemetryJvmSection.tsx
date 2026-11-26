import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { Stack } from "@mantine/core";
import type { TelemetryCurrent, TelemetryRange } from "../api/telemetry";
import { formatCount, formatDuration, formatRatio } from "../utils/telemetryFormat";
import { jvmColumns } from "../utils/telemetryTrend";
import { FirstSampleNote } from "./TelemetryMemorySection";
import TelemetryProcessHeader from "./TelemetryProcessHeader";
import TelemetryTrend from "./TelemetryTrend";
import TelemetryValueTable from "./TelemetryValueTable";

/** The live LISTEN session: up, down, or (not started yet) a dash. */
function listenerState(up: boolean | null | undefined, t: TFunction): string {
  if (up == null) return "—";
  return up ? t("telemetry.jvm.listenerUp") : t("telemetry.jvm.listenerDown");
}

/** JVM extras: per process the CPU, thread count, GC work of the latest interval and the database pool and the live channel, and the trend. */
export default function TelemetryJvmSection({
  current,
  range,
  active,
  processLabels,
}: {
  current: TelemetryCurrent;
  range: TelemetryRange;
  active: boolean;
  processLabels: ReadonlyMap<number, string>;
}) {
  const { t } = useTranslation();
  return (
    <Stack gap="md">
      {current.processes.length === 0 ? (
        <FirstSampleNote intervalSeconds={current.sampleIntervalSeconds} />
      ) : (
        current.processes.map((process) => {
          const label = processLabels.get(process.id)!;
          return (
            <Stack key={process.id} gap="xs">
              <TelemetryProcessHeader process={process} label={label} />
              {process.sampledAt == null ? (
                <FirstSampleNote intervalSeconds={current.sampleIntervalSeconds} />
              ) : (
                <TelemetryValueTable
                  label={t("telemetry.jvm.tableAria", { process: label })}
                  rows={[
                    { label: t("telemetry.jvm.cpu"), value: formatRatio(process.jvm.cpuRatio) },
                    { label: t("telemetry.jvm.threads"), value: formatCount(process.jvm.threads) },
                    { label: t("telemetry.jvm.gcCount"), value: formatCount(process.jvm.gcCount) },
                    { label: t("telemetry.jvm.gcTime"), value: formatDuration(process.jvm.gcTimeMs) },
                    { label: t("telemetry.jvm.poolAcquired"), value: formatCount(process.jvm.poolAcquired) },
                    { label: t("telemetry.jvm.poolIdle"), value: formatCount(process.jvm.poolIdle) },
                    { label: t("telemetry.jvm.poolPending"), value: formatCount(process.jvm.poolPending) },
                    { label: t("telemetry.jvm.poolAllocated"), value: formatCount(process.jvm.poolAllocated) },
                    { label: t("telemetry.jvm.liveSockets"), value: formatCount(process.jvm.liveSockets) },
                    { label: t("telemetry.jvm.liveListener"), value: listenerState(process.jvm.liveListenerUp, t) },
                  ]}
                />
              )}
            </Stack>
          );
        })
      )}
      <TelemetryTrend group="jvm" range={range} columns={jvmColumns} label={t("telemetry.section.jvm")} active={active} />
    </Stack>
  );
}
