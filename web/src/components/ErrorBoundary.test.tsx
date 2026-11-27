import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { renderWithProviders, screen } from "../test/render";
import ErrorBoundary, { RouteErrorBoundary } from "./ErrorBoundary";

function Bomb(): never {
  throw new Error("boom");
}

function RoomNavigation({ crashOnThread = false }: { crashOnThread?: boolean }) {
  const { pathname } = useLocation();
  return (
    <>
      <Link to="/rooms/4">Back to threads</Link>
      <Link to="/rooms/5/threads/1">Another room</Link>
      <RouteErrorBoundary>
        {crashOnThread && pathname === "/rooms/4/threads/1" ? <Bomb /> : <ThreadListState />}
      </RouteErrorBoundary>
    </>
  );
}

function ThreadListState() {
  const [opened, setOpened] = useState(false);
  return <button onClick={() => setOpened(true)}>{opened ? "Filters open" : "Open filters"}</button>;
}

describe("ErrorBoundary", () => {
  test("a render crash swaps to the fallback and the reload button reloads", async () => {
    // React logs the caught error — keep the test output quiet.
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const reload = vi.fn();
    vi.stubGlobal("location", { ...window.location, reload });
    try {
      renderWithProviders(
        <ErrorBoundary>
          <Bomb />
        </ErrorBoundary>,
      );
      expect(screen.getByText("Something went wrong")).toBeInTheDocument();
      await userEvent.setup().click(screen.getByRole("button", { name: "Reload" }));
      expect(reload).toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
      spy.mockRestore();
    }
  });

  test("a caller-supplied fallback replaces the generic one", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      renderWithProviders(
        <ErrorBoundary fallback={<p>custom way out</p>}>
          <Bomb />
        </ErrorBoundary>,
      );
      expect(screen.getByText("custom way out")).toBeInTheDocument();
      expect(screen.queryByText("Something went wrong")).not.toBeInTheDocument();
    } finally {
      spy.mockRestore();
    }
  });

  test("renders children while nothing crashes", () => {
    renderWithProviders(
      <RouteErrorBoundary>
        <div>content</div>
      </RouteErrorBoundary>,
    );
    expect(screen.getByText("content")).toBeInTheDocument();
  });

  test("returning to the same room keeps its healthy list mounted, while a different room starts fresh", async () => {
    renderWithProviders(<RoomNavigation />, { route: "/rooms/4/threads/1" });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Open filters" }));
    await user.click(screen.getByRole("link", { name: "Back to threads" }));
    expect(screen.getByRole("button", { name: "Filters open" })).toBeVisible();
    await user.click(screen.getByRole("link", { name: "Another room" }));
    expect(screen.getByRole("button", { name: "Open filters" })).toBeVisible();
  });

  test("navigation back to the same room recovers from a crashed thread pane", async () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      renderWithProviders(<RoomNavigation crashOnThread />, { route: "/rooms/4/threads/1" });
      expect(screen.getByText("Something went wrong")).toBeVisible();
      await userEvent.setup().click(screen.getByRole("link", { name: "Back to threads" }));
      expect(screen.getByRole("button", { name: "Open filters" })).toBeVisible();
    } finally {
      spy.mockRestore();
    }
  });
});
