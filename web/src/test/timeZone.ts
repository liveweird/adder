import { vi } from "vitest";

/**
 * Pins the zone `browserTimeZone()` reports (it reads `Intl.DateTimeFormat().resolvedOptions().timeZone`) for the calling test,
 * so a list's dates are asserted against a known non-UTC zone whatever the host's own is. Undo it with the returned spy's
 * `mockRestore()` (or `vi.restoreAllMocks()`) in `afterEach`.
 */
export function pinBrowserTimeZone(zone: string) {
  const original = Intl.DateTimeFormat.prototype.resolvedOptions;
  return vi.spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions").mockImplementation(function (this: Intl.DateTimeFormat) {
    return { ...original.call(this), timeZone: zone };
  });
}
