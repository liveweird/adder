import { afterEach, describe, expect, test } from "vitest";
import { useDocumentTitle } from "./useDocumentTitle";
import i18n from "../i18n";
import { renderWithProviders } from "../test/render";

function Page({ title }: { title: string }) {
  useDocumentTitle(title);
  return null;
}

describe("useDocumentTitle", () => {
  afterEach(() => {
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
});
