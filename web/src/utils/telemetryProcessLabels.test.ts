import type { TFunction } from "i18next";
import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { processFixture } from "../test/telemetryFixtures";
import { telemetryProcessLabels } from "./telemetryProcessLabels";

const t = i18n.t.bind(i18n) as TFunction;

describe("telemetryProcessLabels", () => {
  test("uses the process id to choose the newest label when start times tie", () => {
    const startedAt = Date.UTC(2026, 9, 24, 9, 0, 0);
    const labels = telemetryProcessLabels(
      [processFixture({ id: 42, startedAt }), processFixture({ id: 41, startedAt })],
      t,
    );

    expect(labels.get(42)).toBe("adder-app-1");
    expect(labels.get(41)).toBe("adder-app-1 · earlier boot #41");
  });
});
