import { MantineProvider } from "@mantine/core";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { theme } from "../theme";
import UnreadCue, { UnreadAnchor } from "./UnreadCue";

function view(ui: React.ReactElement) {
  return render(
    <MantineProvider theme={theme} env="test">
      {ui}
    </MantineProvider>,
  );
}

describe("UnreadCue", () => {
  it("is a dot without children, hidden from assistive technology", () => {
    const { container } = view(<UnreadCue />);
    const cue = container.querySelector("[data-unread-cue]");
    expect(cue).toHaveAttribute("data-unread-cue", "dot");
    expect(cue).toHaveAttribute("aria-hidden", "true");
    expect(cue).toHaveTextContent("");
  });

  it("is a count badge with children, painted from the unread tokens, never the brand or red", () => {
    const { container } = view(<UnreadCue icon={<svg data-testid="icon" />}>99+</UnreadCue>);
    const cue = container.querySelector<HTMLElement>("[data-unread-cue]")!;
    expect(cue).toHaveAttribute("data-unread-cue", "count");
    expect(cue).toHaveAttribute("aria-hidden", "true");
    expect(cue).toHaveTextContent("99+");
    expect(cue.querySelector("[data-testid=icon]")).not.toBeNull();
    // Painted by the module's class (the tokens), not by Mantine's private --badge-* variables.
    expect(cue.className).toMatch(/count/);
  });

  it("takes the xs size where the host is tight", () => {
    const { container } = view(<UnreadCue size="xs">2</UnreadCue>);
    expect(container.querySelector("[data-unread-cue]")).toHaveAttribute("data-size", "xs");
  });

  it("takes the New word as a count badge too (the conversation's marker)", () => {
    const { container } = view(<UnreadCue>New</UnreadCue>);
    expect(container.querySelector("[data-unread-cue=count]")).toHaveTextContent("New");
  });
});

describe("UnreadAnchor", () => {
  it("draws no cue while nothing is unread, and keeps the flagged child", () => {
    const { container, getByText } = view(
      <UnreadAnchor show={false} count="3">
        <button type="button">Bell</button>
      </UnreadAnchor>,
    );
    expect(getByText("Bell")).toBeInTheDocument();
    expect(container.querySelector("[data-unread-cue]")).toBeNull();
  });

  it("pins a count to the child while shown, or a dot without a count, and carries the title", () => {
    const counted = view(
      <UnreadAnchor show count="3" title="Flagged">
        <button type="button">Bell</button>
      </UnreadAnchor>,
    );
    expect(counted.container.querySelector("[data-unread-cue=count]")).toHaveTextContent("3");
    expect(counted.container.querySelector("[title=Flagged]")).not.toBeNull();
    expect(counted.container.querySelector("[data-unread-cue]")!.className).toMatch(/ring/);
    counted.unmount();
    const dotted = view(
      <UnreadAnchor show>
        <button type="button">Avatar</button>
      </UnreadAnchor>,
    );
    expect(dotted.container.querySelector("[data-unread-cue=dot]")).not.toBeNull();
    dotted.unmount();
    const bare = view(
      <UnreadAnchor show ring={false}>
        <button type="button">Rail</button>
      </UnreadAnchor>,
    );
    expect(bare.container.querySelector("[data-unread-cue]")!.className).not.toMatch(/ring/);
  });
});
