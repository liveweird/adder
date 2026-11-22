import { afterEach, describe, expect, test } from "vitest";
import { useDocumentTitle } from "./useDocumentTitle";
import i18n from "../i18n";
import { renderWithProviders } from "../test/render";
import { setTitleCount } from "../utils/titleCount";
import { act } from "@testing-library/react";

function Page({ title }: { title: string }) {
  useDocumentTitle(title);
  return null;
}

describe("useDocumentTitle", () => {
  afterEach(() => {
    // The count is module state and the suite runs with `isolate: false`.
    setTitleCount(0);
    document.title = "";
  });

  test("titles the tab '{page} · Adder' while the page is mounted and follows a change", () => {
    const { rerender, unmount } = renderWithProviders(<Page title="Users" />);
    expect(document.title).toBe("Users · Adder");
    rerender(<Page title="Rooms" />);
    expect(document.title).toBe("Rooms · Adder");
    unmount();
    expect(document.title).toBe("Adder");
  });

  test("an empty title leaves the bare brand", () => {
    renderWithProviders(<Page title="" />);
    expect(document.title).toBe("Adder");
  });

  test("a page titled with the brand itself is not doubled, in either language", async () => {
    renderWithProviders(<Page title="Adder" />);
    expect(document.title).toBe("Adder");
    await i18n.changeLanguage("pl");
    try {
      renderWithProviders(<Page title="Użytkownicy" />);
      expect(document.title).toBe("Użytkownicy · Adder");
    } finally {
      await i18n.changeLanguage("en");
    }
  });

  test("the unseen count leads the title, follows a change while mounted, and the unmount reset keeps it", () => {
    const { unmount } = renderWithProviders(<Page title="Rooms" />);
    expect(document.title).toBe("Rooms · Adder");
    act(() => setTitleCount(3));
    expect(document.title).toBe("(3) Rooms · Adder");
    act(() => setTitleCount(0));
    expect(document.title).toBe("Rooms · Adder");
    act(() => setTitleCount(1000));
    expect(document.title).toBe("(999+) Rooms · Adder");
    unmount();
    expect(document.title).toBe("(999+) Adder");
  });

  test("a count is spelled in front of the brand alone, and a page changing keeps it", () => {
    setTitleCount(4);
    const { rerender } = renderWithProviders(<Page title="" />);
    expect(document.title).toBe("(4) Adder");
    rerender(<Page title="Users" />);
    expect(document.title).toBe("(4) Users · Adder");
  });
});
