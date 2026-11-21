import { describe, expect, test } from "vitest";
import { render } from "@testing-library/react";
import { fillSlots, slot } from "./slots";

const html = (node: React.ReactNode) => {
  const { container } = render(<div>{node}</div>);
  return container.firstElementChild!.innerHTML;
};

describe("fillSlots", () => {
  test("puts a node where its marker stands and keeps the words around it", () => {
    expect(html(fillSlots(`Owner: ${slot("name")}, since monday`, { name: <a href="/x">Ada</a> }))).toBe('Owner: <a href="/x">Ada</a>, since monday');
  });

  test("fills several markers, and the same marker twice", () => {
    expect(html(fillSlots(`${slot("a")} and ${slot("b")} and ${slot("a")}`, { a: <b>A</b>, b: <i>B</i> }))).toBe("<b>A</b> and <i>B</i> and <b>A</b>");
  });

  test("text with no marker comes out as it went in", () => {
    expect(html(fillSlots("Plain sentence", {}))).toBe("Plain sentence");
  });

  test("a marker that names no node renders nothing", () => {
    expect(html(fillSlots(`a${slot("gone")}b`, { other: <b>x</b> }))).toBe("ab");
  });

  test("a marker named like an Object.prototype member renders nothing", () => {
    expect(html(fillSlots(`a${slot("constructor")}${slot("toString")}b`, {}))).toBe("ab");
  });

  // Why this is not <Trans>: the values of a sentence are what people typed, so markup in one is a character string, never an element.
  test("markup in the surrounding text is text, never an element", () => {
    const out = html(fillSlots(`<strong>x</strong> <person>y</person> ${slot("name")}`, { name: <a href="/p/1">Ada</a> }));
    expect(out).toBe('&lt;strong&gt;x&lt;/strong&gt; &lt;person&gt;y&lt;/person&gt; <a href="/p/1">Ada</a>');
  });
});
