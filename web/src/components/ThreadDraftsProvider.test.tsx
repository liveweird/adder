import { describe, expect, test } from "vitest";
import { render, screen } from "@testing-library/react";
import ThreadDraftsProvider from "./ThreadDraftsProvider";
import { useThreadDrafts } from "../hooks/useThreadDrafts";
import { emptyThreadForm } from "../utils/threadForm";

function Reader({ roomId }: { roomId: number }) {
  return <p>{useThreadDrafts().get(roomId)?.title ?? "none"}</p>;
}
function Writer({ roomId }: { roomId: number }) {
  useThreadDrafts().set(roomId, { ...emptyThreadForm([]), title: "kept" });
  return null;
}

describe("ThreadDraftsProvider", () => {
  test("one store for everything beneath it, which a remount of a child does not reset", () => {
    const { rerender } = render(
      <ThreadDraftsProvider>
        <Writer roomId={5} />
      </ThreadDraftsProvider>,
    );
    rerender(
      <ThreadDraftsProvider>
        <Reader roomId={5} />
      </ThreadDraftsProvider>,
    );
    expect(screen.getByText("kept")).toBeInTheDocument();
  });
});
