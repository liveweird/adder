import { describe, expect, test } from "vitest";
import { render, screen } from "@testing-library/react";
import { createKnownNames, KnownNamesContext, useKnownNames } from "./knownNames";

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
