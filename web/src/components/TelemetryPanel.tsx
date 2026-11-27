import { useState, type ComponentType } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { Accordion, ActionIcon, Alert, AppShell, Group, ScrollArea, SegmentedControl, Stack, Text, Title } from "@mantine/core";
import { IconActivityHeartbeat, IconX } from "@tabler/icons-react";
import { getTelemetryCurrent, type TelemetryCurrent, type TelemetryRange } from "../api/telemetry";
import { isOneOf, useStoredState } from "../hooks/useStoredState";
import { formatDateTime } from "../utils/formatDate";
import { roleName } from "../utils/telemetryRole";
import { telemetryProcessLabels } from "../utils/telemetryProcessLabels";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import TelemetryJvmSection from "./TelemetryJvmSection";
import TelemetryMemorySection from "./TelemetryMemorySection";
import TelemetryRequestsSection from "./TelemetryRequestsSection";
import TelemetryStorageSection from "./TelemetryStorageSection";
import classes from "./TelemetryPanel.module.css";

const SECTION_IDS = ["memory", "storage", "requests", "jvm"] as const;
type SectionId = (typeof SECTION_IDS)[number];
const RANGES = ["24h", "7d", "90d"] as const;

/** `current` is refetched every minute while the panel is open (the panel only exists while open), never in a background tab. */
const REFRESH_MS = 60_000;

const VIEWS: Record<SectionId, ComponentType<{ current: TelemetryCurrent; range: TelemetryRange; active: boolean; processLabels: ReadonlyMap<number, string> }>> = {
  memory: TelemetryMemorySection,
  storage: TelemetryStorageSection,
  requests: TelemetryRequestsSection,
  jvm: TelemetryJvmSection,
};

const isRange = isOneOf(RANGES);
const isSectionList = (value: unknown): value is SectionId[] =>
  Array.isArray(value) && value.every((entry) => SECTION_IDS.includes(entry as SectionId));

/**
 * The telemetry panel (ADMIN only, the shell's right-hand aside): the latest sample of Adder's own process in
 * four accordion sections - memory, storage, requests, JVM - each with its 24 h / 7 d / 90 d trend. The range and the
 * expanded sections are remembered on this device. The component is mounted only while the panel is open, so the one-minute
 * refresh of `current` stops with it, and a section's history is only requested while it is expanded.
 */
export default function TelemetryPanel({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const [range, setRange] = useStoredState<TelemetryRange>("telemetry.range", "24h", isRange);
  const [expanded, setExpanded] = useStoredState<SectionId[]>("telemetry.sections", ["memory"], isSectionList);
  const current = useQuery({
    queryKey: ["telemetry", "current"],
    queryFn: ({ signal }) => getTelemetryCurrent(signal),
    refetchInterval: REFRESH_MS,
    refetchIntervalInBackground: false,
    staleTime: REFRESH_MS / 2,
  });
  // A section stays rendered once opened (so it can finish its close transition and keep its figures); its history queries run
  // only while it is expanded (`active`).
  const [opened, setOpened] = useState<ReadonlySet<SectionId>>(() => new Set(expanded));
  const data = current.data;
  const processLabels = telemetryProcessLabels(data?.processes ?? [], t);
  const sampled = data?.processes.reduce<number | null>((newest, p) => (p.sampledAt == null ? newest : Math.max(newest ?? 0, p.sampledAt)), null);
  return (
    <>
      <AppShell.Section className={classes.header}>
        <Stack gap="sm">
          <Group justify="space-between" wrap="nowrap">
            <Group gap="sm" wrap="nowrap" miw={0}>
              <div className={classes.mark} aria-hidden="true">
                <IconActivityHeartbeat size={18} />
              </div>
              <Stack gap={1} miw={0}>
                <Title order={2} size="h4" className={classes.title}>
                  {t("telemetry.title")}
                </Title>
                {sampled != null && (
                  <Text size="xs" c="dimmed" className={classes.sampled}>
                    {t("telemetry.sampledAt", { time: formatDateTime(sampled) })}
                  </Text>
                )}
              </Stack>
            </Group>
            <ActionIcon variant="subtle" color="gray" aria-label={t("common.action.close")} onClick={onClose}>
              <IconX size={18} aria-hidden="true" />
            </ActionIcon>
          </Group>
          <SegmentedControl
            size="xs"
            fullWidth
            className={classes.range}
            aria-label={t("telemetry.range.label")}
            value={range}
            onChange={(value) => setRange(value as TelemetryRange)}
            data={RANGES.map((value) => ({ value, label: t(`telemetry.range.${value}`) }))}
          />
        </Stack>
      </AppShell.Section>
      <AppShell.Section grow component={ScrollArea} type="hover" scrollbarSize={6} className={classes.scroller}>
        <Stack className={classes.body} gap="sm">
          {current.isError && data === undefined && <ErrorAlert error={current.error} />}
          {current.isError && data !== undefined && <ErrorAlert error={current.error} title={t("telemetry.refreshFailed")} />}
          {data === undefined && !current.isError && <LoadingBlock />}
          {data?.processes
            .filter((process) => process.stale)
            .map((process) => (
              <Alert key={process.id} color="orange" variant="light" title={t("telemetry.stale.title", { role: roleName(t, process.role) })}>
                {t("telemetry.stale.message", { instance: processLabels.get(process.id)! })}
              </Alert>
            ))}
          {data !== undefined && (
            <Accordion
              multiple
              order={3}
              value={expanded}
              variant="default"
              className={classes.sections}
              classNames={{ item: classes.section, control: classes.sectionControl, panel: classes.sectionPanel }}
              onChange={(value) => {
                setExpanded(value as SectionId[]);
                setOpened((previous) => new Set([...previous, ...(value as SectionId[])]));
              }}
            >
              {SECTION_IDS.map((id) => {
                const View = VIEWS[id];
                return (
                  <Accordion.Item key={id} value={id}>
                    <Accordion.Control>{t(`telemetry.section.${id}`)}</Accordion.Control>
                    <Accordion.Panel>
                      {(expanded.includes(id) || opened.has(id)) && (
                        <View current={data} range={range} active={expanded.includes(id)} processLabels={processLabels} />
                      )}
                    </Accordion.Panel>
                  </Accordion.Item>
                );
              })}
            </Accordion>
          )}
        </Stack>
      </AppShell.Section>
    </>
  );
}
