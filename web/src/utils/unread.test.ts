import { describe, expect, test } from "vitest";
import { UNREAD_COUNT_CAP, unreadBadge, unseenByRoom, unseenThreadNumbers } from "./unread";

describe("unread helpers", () => {
  test("the count shows as it is up to 99 and as 99+ at the server's cap of 100", () => {
    expect(UNREAD_COUNT_CAP).toBe(100);
    expect([1, 7, 99].map(unreadBadge)).toEqual(["1", "7", "99"]);
    expect(unreadBadge(100)).toBe("99+");
    expect(unreadBadge(250)).toBe("99+");
  });

  test("the unseen notices are counted per room", () => {
    const counts = unseenByRoom([{ roomId: 1 }, { roomId: 2 }, { roomId: 1 }]);
    expect(counts.get(1)).toBe(2);
    expect(counts.get(2)).toBe(1);
    expect(counts.get(3)).toBeUndefined();
    expect(unseenByRoom([]).size).toBe(0);
  });

  test("the threads of one room that hold an unseen notice are listed by number", () => {
    const items = [
      { roomId: 1, threadNumber: 4 },
      { roomId: 1, threadNumber: 4 },
      { roomId: 1, threadNumber: 9 },
      { roomId: 2, threadNumber: 5 },
    ];
    expect([...unseenThreadNumbers(items, 1)].sort()).toEqual([4, 9]);
    expect(unseenThreadNumbers(items, 7).size).toBe(0);
  });
});
