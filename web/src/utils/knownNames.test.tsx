import { afterEach, describe, expect, test } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { clearSession, persistSession } from "../api/session";
import { createKnownNames, KnownNamesContext, useKnownNames, useSessionKnownNames, type KnownNames } from "./knownNames";

const login = (userId: number) => ({ token: `t${userId}`, expiresAt: 1, refreshToken: `r${userId}`, refreshExpiresAt: 2, userId, roles: [], disabledFeatures: [], language: "en" as const });

describe("createKnownNames", () => {
  test("remembers the names it is given, and a later name for the same id wins", () => {
    const names = createKnownNames();
    expect(names.get(5)).toBeUndefined();
    names.remember([
      { userId: 5, name: "Ada" },
      { userId: 6, name: "Bob" },
    ]);
    names.remember([{ userId: 5, name: "Ada Lovelace" }]);
    expect(names.get(5)).toBe("Ada Lovelace");
    expect(names.get(6)).toBe("Bob");
  });
});

describe("useKnownNames", () => {
  function Probe() {
    const names = useKnownNames();
    return <output aria-label="who">{names.get(5) ?? "nobody"}</output>;
  }

  test("reads the shell's store, and with no provider a private one", () => {
    const shared = createKnownNames();
    shared.remember([{ userId: 5, name: "Ada" }]);
    const { unmount } = render(
      <KnownNamesContext.Provider value={shared}>
        <Probe />
      </KnownNamesContext.Provider>,
    );
    expect(screen.getByLabelText("who")).toHaveTextContent("Ada");
    unmount();
    render(<Probe />);
    expect(screen.getByLabelText("who")).toHaveTextContent("nobody");
  });
});

describe("useKnownNames without a provider", () => {
  test("keeps one private store for as long as the component lives", () => {
    const seen: KnownNames[] = [];
    function Probe() {
      const names = useKnownNames();
      seen.push(names);
      return null;
    }
    const { rerender } = render(<Probe />);
    rerender(<Probe />);
    expect(seen[1]).toBe(seen[0]);
  });
});

describe("useSessionKnownNames", () => {
  afterEach(() => {
    act(() => clearSession());
    localStorage.clear();
  });

  function Holder({ out }: { out: { current: KnownNames | null } }) {
    out.current = useSessionKnownNames();
    return null;
  }

  test("a new sign-in, a sign-out and another tab's sign-in each start with an empty store; a re-render keeps it", () => {
    act(() => persistSession(login(1)));
    const out: { current: KnownNames | null } = { current: null };
    const { rerender } = render(<Holder out={out} />);
    const first = out.current!;
    first.remember([{ userId: 5, name: "Ada" }]);
    rerender(<Holder out={out} />);
    expect(out.current).toBe(first);
    expect(first.get(5)).toBe("Ada");

    act(() => persistSession(login(2)));
    const second = out.current!;
    expect(second).not.toBe(first);
    expect(second.get(5)).toBeUndefined();

    second.remember([{ userId: 5, name: "Ada" }]);
    act(() => clearSession());
    expect(out.current!.get(5)).toBeUndefined();

    // Another tab signed in as someone else: only the stored identity changes, and the storage event says so.
    const third = out.current!;
    third.remember([{ userId: 5, name: "Ada" }]);
    act(() => {
      localStorage.setItem("adder.auth.sessionIdentity", "another-tab");
      window.dispatchEvent(new StorageEvent("storage", { key: "adder.auth.sessionIdentity" }));
    });
    expect(out.current!).not.toBe(third);
    expect(out.current!.get(5)).toBeUndefined();
  });
});
