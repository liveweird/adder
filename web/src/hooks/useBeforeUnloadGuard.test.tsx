import { describe, expect, test } from "vitest";
import { renderHook } from "@testing-library/react";
import { useBeforeUnloadGuard } from "./useBeforeUnloadGuard";

function fireBeforeUnload(): BeforeUnloadEvent {
  const event = new Event("beforeunload", { cancelable: true }) as BeforeUnloadEvent;
  window.dispatchEvent(event);
  return event;
}

describe("useBeforeUnloadGuard", () => {
  test("a dirty value prevents the unload, a clean one lets it pass", () => {
    const { rerender } = renderHook(({ dirty }) => useBeforeUnloadGuard(dirty), { initialProps: { dirty: true } });
    expect(fireBeforeUnload().defaultPrevented).toBe(true);
    rerender({ dirty: false });
    expect(fireBeforeUnload().defaultPrevented).toBe(false);
  });

  test("a reader is asked afresh at unload time and handed back as the returned reader", () => {
    let dirty = false;
    const { result } = renderHook(() => useBeforeUnloadGuard(() => dirty));
    expect(fireBeforeUnload().defaultPrevented).toBe(false);
    dirty = true;
    expect(fireBeforeUnload().defaultPrevented).toBe(true);
    expect(result.current()).toBe(true);
  });

  test("the listener is removed on unmount", () => {
    const { unmount } = renderHook(() => useBeforeUnloadGuard(true));
    unmount();
    expect(fireBeforeUnload().defaultPrevented).toBe(false);
  });
});
