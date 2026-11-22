import { afterEach, describe, expect, test, vi } from "vitest";
import { composeTitle, getTitleCount, setTitleCount, subscribeTitleCount } from "./titleCount";

describe("titleCount", () => {
  afterEach(() => setTitleCount(0));

  test("composes '(n) Page · Adder', with no prefix at zero", () => {
    expect(composeTitle("Rooms", "Adder", 3)).toBe("(3) Rooms · Adder");
    expect(composeTitle("Rooms", "Adder", 0)).toBe("Rooms · Adder");
  });

  test("the brand alone, or a page that IS the brand, is never doubled, with or without a count", () => {
    expect(composeTitle("", "Adder", 0)).toBe("Adder");
    expect(composeTitle("Adder", "Adder", 0)).toBe("Adder");
    expect(composeTitle("", "Adder", 12)).toBe("(12) Adder");
    expect(composeTitle("Adder", "Adder", 12)).toBe("(12) Adder");
  });

  test("the bell's cap reads 999+", () => {
    expect(composeTitle("Rooms", "Adder", 999)).toBe("(999) Rooms · Adder");
    expect(composeTitle("Rooms", "Adder", 1000)).toBe("(999+) Rooms · Adder");
  });

  test("the store keeps a whole number of at least zero and tells its listeners only about a change", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeTitleCount(listener);
    setTitleCount(-4);
    setTitleCount(Number.NaN);
    expect(getTitleCount()).toBe(0);
    expect(listener).not.toHaveBeenCalled();
    setTitleCount(2.9);
    expect(getTitleCount()).toBe(2);
    setTitleCount(2);
    expect(listener).toHaveBeenCalledTimes(1);
    unsubscribe();
    setTitleCount(5);
    expect(listener).toHaveBeenCalledTimes(1);
  });
});
