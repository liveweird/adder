import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import Snippet from "./Snippet";

describe("Snippet", () => {
  test("segments become text, and the words that matched are <mark>", () => {
    const { container } = renderWithProviders(
      <p>
        <Snippet
          segments={[
            { text: "the ", hit: false },
            { text: "outage", hit: true },
            { text: " started", hit: false },
          ]}
        />
      </p>,
    );
    expect(container.querySelector("p")?.textContent).toBe("the outage started");
    const marks = container.querySelectorAll("mark");
    expect([...marks].map((mark) => mark.textContent)).toEqual(["outage"]);
  });

  test("markup in a segment is text: an <img onerror> neither renders nor runs, in a hit or out of one", () => {
    const evil = '<img src=x onerror="document.title=\'pwned\'">';
    const { container } = renderWithProviders(
      <p>
        <Snippet
          segments={[
            { text: evil, hit: false },
            { text: "<script>alert(1)</script>", hit: true },
          ]}
        />
      </p>,
    );
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("script")).toBeNull();
    expect(container.querySelector("p")?.textContent).toBe(`${evil}<script>alert(1)</script>`);
    expect(screen.getByText("<script>alert(1)</script>").tagName).toBe("MARK");
    expect(document.title).not.toBe("pwned");
  });

  test("Markdown in a segment stays as typed", () => {
    const { container } = renderWithProviders(
      <p>
        <Snippet segments={[{ text: "**bold** and `code` and [a link](https://example.com)", hit: false }]} />
      </p>,
    );
    expect(container.querySelector("strong, code, a")).toBeNull();
    expect(container.querySelector("p")?.textContent).toBe("**bold** and `code` and [a link](https://example.com)");
  });

  test("no segments, nothing", () => {
    const { container } = renderWithProviders(
      <p>
        <Snippet segments={[]} />
      </p>,
    );
    expect(container.querySelector("p")?.textContent).toBe("");
  });
});
