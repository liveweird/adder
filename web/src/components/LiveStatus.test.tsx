import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, screen } from "@testing-library/react";
import LiveStatus from "./LiveStatus";
import { renderWithProviders } from "../test/render";

describe("LiveStatus", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  test("the status region is always there, and empty while the channel is live", () => {
    const { rerender } = renderWithProviders(<LiveStatus status="connecting" />);
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
    rerender(<LiveStatus status="live" />);
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
    rerender(<LiveStatus status="idle" />);
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
  });

  test("an empty region leaves the header's layout (no gap in its flex group); a badge puts it back", () => {
    const { rerender } = renderWithProviders(<LiveStatus status="live" />);
    expect(screen.getByRole("status")).toHaveStyle({ position: "absolute" });
    rerender(<LiveStatus status="paused" />);
    expect(screen.getByRole("status")).not.toHaveStyle({ position: "absolute" });
  });

  test("a connection that is down shows nothing for five seconds, then Reconnecting… with its hint", () => {
    renderWithProviders(<LiveStatus status="down" />);
    act(() => vi.advanceTimersByTime(4999));
    expect(screen.queryByText("Reconnecting…")).not.toBeInTheDocument();
    act(() => vi.advanceTimersByTime(1));
    const region = screen.getByRole("status");
    expect(region).toHaveTextContent("Reconnecting…");
    expect(region).toHaveTextContent("Live updates paused. The page catches up as soon as the connection is back.");
  });

  test("a recovery inside the five seconds never shows the badge, and one after it removes it", () => {
    const { rerender } = renderWithProviders(<LiveStatus status="down" />);
    act(() => vi.advanceTimersByTime(3000));
    rerender(<LiveStatus status="live" />);
    act(() => vi.advanceTimersByTime(10_000));
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
    rerender(<LiveStatus status="down" />);
    act(() => vi.advanceTimersByTime(5000));
    expect(screen.getByText("Reconnecting…")).toBeInTheDocument();
    rerender(<LiveStatus status="live" />);
    expect(screen.queryByText("Reconnecting…")).not.toBeInTheDocument();
  });

  test("a second outage starts its own five seconds", () => {
    const { rerender } = renderWithProviders(<LiveStatus status="down" />);
    act(() => vi.advanceTimersByTime(5000));
    rerender(<LiveStatus status="live" />);
    rerender(<LiveStatus status="down" />);
    expect(screen.queryByText("Reconnecting…")).not.toBeInTheDocument();
    act(() => vi.advanceTimersByTime(5000));
    expect(screen.getByText("Reconnecting…")).toBeInTheDocument();
  });

  test("a tab refused for having too many siblings says so at once, with the way out", () => {
    renderWithProviders(<LiveStatus status="paused" />);
    const region = screen.getByRole("status");
    expect(region).toHaveTextContent("Live updates off");
    expect(region).toHaveTextContent("Too many of your tabs receive live updates. Close one; this tab tries again in a minute.");
  });
});
