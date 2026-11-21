import { useState } from "react";
import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { Link } from "react-router-dom";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { useCloseOnPageChange } from "./useCloseOnPageChange";

/** A stand-in drawer: open flag, a trigger, links inside it, and the shell's main region (the skip link's target). */
function Harness() {
  const [opened, setOpened] = useState(false);
  const [returnFocus, setReturnFocus] = useState(true);
  const noteOpened = useCloseOnPageChange(opened, () => {
    setReturnFocus(false);
    setOpened(false);
  });
  return (
    <>
      <main id="main-content" tabIndex={-1} aria-label="main" />
      <button
        onClick={() => {
          setReturnFocus(true);
          noteOpened();
          setOpened(true);
        }}
      >
        open
      </button>
      <output aria-label="state">{`${opened ? "open" : "closed"} ${returnFocus ? "returns" : "no-return"}`}</output>
      {opened && (
        <>
          <Link to="/people/7">person</Link>
          <Link to="/rooms/12/threads/3">thread</Link>
          <Link to="/rooms/12/threads/3?message=9">same page, other query</Link>
        </>
      )}
    </>
  );
}

describe("useCloseOnPageChange", () => {
  test("a link to another page closes the drawer without returning focus, and the main region takes it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/rooms/12" });
    await user.click(screen.getByRole("button", { name: "open" }));
    await user.click(screen.getByRole("link", { name: "person" }));
    await waitFor(() => expect(screen.getByLabelText("state")).toHaveTextContent("closed no-return"));
    await waitFor(() => expect(screen.getByLabelText("main")).toHaveFocus());
  });

  test("a thread route takes the focus itself, so the hook leaves it alone", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/rooms/12" });
    await user.click(screen.getByRole("button", { name: "open" }));
    await user.click(screen.getByRole("link", { name: "thread" }));
    await waitFor(() => expect(screen.getByLabelText("state")).toHaveTextContent("closed no-return"));
    expect(screen.getByLabelText("main")).not.toHaveFocus();
  });

  test("a change of the query alone is not a change of page", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />, { route: "/rooms/12/threads/3" });
    await user.click(screen.getByRole("button", { name: "open" }));
    await user.click(screen.getByRole("link", { name: "same page, other query" }));
    expect(screen.getByLabelText("state")).toHaveTextContent("open returns");
  });
});
