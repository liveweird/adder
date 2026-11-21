import type { ReactNode } from "react";
import { describe, expect, test } from "vitest";
import { renderHook } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { useThreadRefHref } from "./useThreadRefHref";

const hrefAt = (route: string) => {
  const wrapper = ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>;
  return renderHook(() => useThreadRefHref(12), { wrapper }).result.current;
};

describe("useThreadRefHref", () => {
  test("a number leads to that thread of the same room, carrying the thread list's keys and no other", () => {
    const href = hrefAt("/rooms/12/threads/3?scope=closed&order=number&tag=db&message=9&focus=1");
    expect(href(42)).toBe("/rooms/12/threads/42?scope=closed&order=number&tag=db");
  });

  test("a bare list gives a bare link", () => {
    expect(hrefAt("/rooms/12/threads/3")(42)).toBe("/rooms/12/threads/42");
  });

  test("from the search tab the search's own scope and page are not the list's, so none is carried", () => {
    expect(hrefAt("/rooms/12/search?q=x&scope=closed&page=3")(42)).toBe("/rooms/12/threads/42");
  });
});
