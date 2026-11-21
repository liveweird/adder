import { describe, expect, test, vi } from "vitest";
import { lookingAt, startLooking } from "./lookingAt";

describe("lookingAt", () => {
  test("names the one registered thread and nothing else", () => {
    const looking = { roomId: 5, threadNumber: 7, covers: () => true, acknowledge: vi.fn() };
    const stop = startLooking(looking);
    expect(lookingAt(5, 7)).toBe(looking);
    expect(lookingAt(5, 8)).toBeNull();
    expect(lookingAt(6, 7)).toBeNull();
    expect(lookingAt(5, undefined)).toBeNull();
    stop();
    expect(lookingAt(5, 7)).toBeNull();
  });

  test("an earlier registration's cleanup does not undo a later one", () => {
    const first = { roomId: 5, threadNumber: 7, covers: () => true, acknowledge: vi.fn() };
    const second = { roomId: 5, threadNumber: 8, covers: () => true, acknowledge: vi.fn() };
    const stopFirst = startLooking(first);
    const stopSecond = startLooking(second);
    stopFirst();
    expect(lookingAt(5, 8)).toBe(second);
    stopSecond();
    expect(lookingAt(5, 8)).toBeNull();
  });
});
