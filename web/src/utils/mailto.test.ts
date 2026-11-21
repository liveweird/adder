import { describe, expect, test } from "vitest";
import { mailtoHref } from "./mailto";

describe("mailtoHref", () => {
  test("keeps a plain address readable, the @ included", () => {
    expect(mailtoHref("ada@example.test")).toBe("mailto:ada@example.test");
  });

  test("percent-encodes what would start a query, a fragment or another header", () => {
    expect(mailtoHref("a?cc=evil@x.test")).toBe("mailto:a%3Fcc%3Devil@x.test");
    expect(mailtoHref("a&b@example.test")).toBe("mailto:a%26b@example.test");
    expect(mailtoHref("a#b@example.test")).toBe("mailto:a%23b@example.test");
    expect(mailtoHref("a b@example.test")).toBe("mailto:a%20b@example.test");
  });

  test("a draft appends its own query after the encoded address", () => {
    const href = `${mailtoHref("a?b@example.test")}?subject=Hi`;
    expect(new URL(href).search).toBe("?subject=Hi");
  });
});
