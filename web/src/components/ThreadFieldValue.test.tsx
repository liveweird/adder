import { describe, expect, test } from "vitest";
import ThreadFieldValue from "./ThreadFieldValue";
import { renderWithProviders, screen } from "../test/render";

const OPTIONS = [
  { key: "s1", label: "S1" },
  { key: "s2", label: "S2" },
];
const NAMES = { "12": "Ann Owner" };

function show(type: "TEXT" | "NUMBER" | "DATE" | "SELECT" | "MULTI_SELECT" | "USER" | "URL", value: string | number | string[] | null | undefined) {
  const { container } = renderWithProviders(<ThreadFieldValue field={{ type, options: type === "SELECT" || type === "MULTI_SELECT" ? OPTIONS : [] }} value={value} userNames={NAMES} />);
  return container;
}

describe("ThreadFieldValue", () => {
  test("an empty value (missing, null, no options) reads as Not set", () => {
    show("TEXT", undefined);
    expect(screen.getByText("Not set")).toBeInTheDocument();
  });

  test("an empty option list is Not set too", () => {
    show("MULTI_SELECT", []);
    expect(screen.getByText("Not set")).toBeInTheDocument();
  });

  test("TEXT, NUMBER and DATE read as their text", () => {
    expect(show("TEXT", "db-3")).toHaveTextContent("db-3");
    expect(show("NUMBER", 7.5)).toHaveTextContent("7.5");
    expect(show("DATE", "2026-06-01")).toHaveTextContent("2026-06-01");
  });

  test("a SELECT shows the option's label; a key the workflow no longer lists shows as it is with a removed-option hint", () => {
    expect(show("SELECT", "s2")).toHaveTextContent("S2");
    const gone = show("SELECT", "s9");
    expect(gone).toHaveTextContent("s9 (removed option)");
  });

  test("a MULTI_SELECT lists the labels, a removed option marked", () => {
    expect(show("MULTI_SELECT", ["s1", "s2"])).toHaveTextContent("S1, S2");
    expect(show("MULTI_SELECT", ["s1", "gone"])).toHaveTextContent("S1, gone (removed option)");
  });

  test("a USER reads as the name the thread carries; an unknown id as #id", () => {
    expect(show("USER", 12)).toHaveTextContent("Ann Owner");
    expect(show("USER", 99)).toHaveTextContent("#99");
  });

  test("a URL is a link that opens in a new tab without the opener; anything but http(s) is plain text", () => {
    show("URL", "https://example.test/runbook");
    const link = screen.getByRole("link", { name: "https://example.test/runbook" });
    expect(link).toHaveAttribute("href", "https://example.test/runbook");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    show("URL", "javascript:alert(1)");
    expect(screen.getByText("javascript:alert(1)")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "javascript:alert(1)" })).not.toBeInTheDocument();
    show("URL", "not a url");
    expect(screen.queryByRole("link", { name: "not a url" })).not.toBeInTheDocument();
  });
});
