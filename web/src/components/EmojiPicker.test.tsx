import { afterEach, describe, expect, test, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import i18n from "../i18n";
import EmojiPicker from "./EmojiPicker";

// Record the props the emoji-mart Picker receives — the component's whole job is passing
// the right ones (data + i18n explicitly, or emoji-mart silently fetches them from a CDN).
const picker = vi.hoisted(() => ({ create: vi.fn(), update: vi.fn() }));
vi.mock("emoji-mart", () => ({
  Picker: function (options: Record<string, unknown>) {
    picker.create(options);
    return Object.assign(document.createElement("em-emoji-picker"), { update: picker.update });
  },
}));
vi.mock("@emoji-mart/data", () => ({ default: { marker: "data" } }));
vi.mock("@emoji-mart/data/i18n/en.json", () => ({ default: { marker: "en" } }));
vi.mock("@emoji-mart/data/i18n/pl.json", () => ({ default: { marker: "pl" } }));

function renderPicker(colorScheme: "light" | "dark" = "light", onSelect = vi.fn()) {
  render(
    <MantineProvider env="test" forceColorScheme={colorScheme}>
      <EmojiPicker onSelect={onSelect} />
    </MantineProvider>,
  );
  return onSelect;
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("EmojiPicker", () => {
  test("passes the bundled data and English i18n explicitly (never the CDN default)", () => {
    renderPicker();
    expect(picker.create).toHaveBeenCalledWith(expect.objectContaining({
      data: { marker: "data" }, i18n: { marker: "en" }, theme: "light", previewPosition: "none",
    }));
  });

  test("follows the dark color scheme", () => {
    renderPicker("dark");
    expect(picker.create).toHaveBeenCalledWith(expect.objectContaining({ theme: "dark" }));
  });

  test("uses the Polish i18n set under the pl locale", async () => {
    await i18n.changeLanguage("pl");
    try {
      renderPicker();
      expect(picker.create).toHaveBeenCalledWith(expect.objectContaining({ i18n: { marker: "pl" } }));
    } finally {
      await i18n.changeLanguage("en");
    }
  });

  test("maps onEmojiSelect to onSelect with the native character", () => {
    const onSelect = renderPicker();
    picker.create.mock.calls[0][0].onEmojiSelect({ native: "🥬" });
    expect(onSelect).toHaveBeenCalledWith("🥬");
  });

  test("updates the mounted picker when the theme changes and removes it on unmount", () => {
    const view = render(
      <MantineProvider env="test" forceColorScheme="light"><EmojiPicker onSelect={vi.fn()} /></MantineProvider>,
    );
    const element = view.container.querySelector("em-emoji-picker");
    expect(element).toBeInTheDocument();
    view.rerender(
      <MantineProvider env="test" forceColorScheme="dark"><EmojiPicker onSelect={vi.fn()} /></MantineProvider>,
    );
    expect(view.container.querySelector("em-emoji-picker")).toBe(element);
    expect(picker.create).toHaveBeenCalledOnce();
    expect(picker.update).toHaveBeenCalledWith(expect.objectContaining({ theme: "dark" }));
    view.unmount();
    expect(element).not.toBeInTheDocument();
  });
});
