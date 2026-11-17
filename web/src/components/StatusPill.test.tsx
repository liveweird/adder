import { describe, expect, test } from "vitest";
import StatusPill from "./StatusPill";
import { renderWithProviders, screen } from "../test/render";

describe("StatusPill", () => {
  test("renders its label with the hue's dot custom property and the min-width guard", () => {
    renderWithProviders(<StatusPill color="teal">Done</StatusPill>);
    const pill = screen.getByText("Done").closest(".mantine-Badge-root") as HTMLElement;
    expect(pill.style.getPropertyValue("--pill-dot")).toBe("light-dark(var(--mantine-color-teal-6), var(--mantine-color-teal-4))");
    expect(pill.style.minWidth).toBe("max-content");
    expect(pill.querySelector("[aria-hidden='true']")).toBeNull();
  });

  test("dot adds the decorative hue dot, ariaLabel names the pill", () => {
    renderWithProviders(
      <StatusPill color="blue" dot size="sm" ariaLabel="Status: In progress">
        In progress
      </StatusPill>,
    );
    const pill = screen.getByLabelText("Status: In progress");
    expect(pill).toHaveTextContent("In progress");
    expect(pill.querySelector("[aria-hidden='true']")).not.toBeNull();
  });

  test("a shade-suffixed colour still yields the bare hue for the dot", () => {
    renderWithProviders(<StatusPill color="orange.8">Warn</StatusPill>);
    const pill = screen.getByText("Warn").closest(".mantine-Badge-root") as HTMLElement;
    expect(pill.style.getPropertyValue("--pill-dot")).toContain("--mantine-color-orange-6");
  });
});
