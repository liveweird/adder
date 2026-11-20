// Test helper: the tab's visibility (`document.visibilityState`) under happy-dom, which has no tab to hide. `setTabVisible(false)` hides it and
// fires `visibilitychange` as a browser does; `resetTabVisibility()` (call it in `afterEach`: the suite runs with `isolate: false`) restores the real getter.
import { act } from "@testing-library/react";

let state: "visible" | "hidden" | null = null;

export function setTabVisible(visible: boolean): void {
  state = visible ? "visible" : "hidden";
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
  act(() => {
    document.dispatchEvent(new Event("visibilitychange"));
  });
}

export function resetTabVisibility(): void {
  state = null;
  // The own property shadows the prototype's getter; removing it brings the real one back.
  Reflect.deleteProperty(document, "visibilityState");
}
