import { accountMenu, expect, login, test } from "./helpers";

// The app shell chrome that isn't owned by any single page: mobile navigation, the icon rail
// (desktop navbar collapse) and the account menu's colour-scheme switch. The two preferences
// are DEVICE-level state (plain localStorage, no server sync — unlike the language switch in
// i18n.spec.ts), so this spec owns no server-side state at all.
test.describe("mobile workspace navigation", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test("the mobile navigation drawer is labelled, restores focus on Escape and focuses the destination after navigation", async ({
    page,
  }) => {
    await login(page);
    const toggle = page.getByRole("button", { name: "Toggle navigation" });
    const drawer = page.getByRole("dialog", { name: "Navigation" });

    await toggle.click();
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole("navigation", { name: "Navigation" })).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(drawer).toBeHidden();
    await expect(toggle).toBeFocused();

    await toggle.click();
    await drawer.getByRole("link", { name: "All rooms", exact: true }).click();
    await expect(page).toHaveURL(/\/rooms$/);
    await expect(drawer).toBeHidden();
    await expect(page.locator("#main-content")).toBeFocused();
  });
});

test("the icon rail collapses and expands the navbar, remembered across reload", async ({ page }) => {
  await login(page);
  const toggle = page.getByRole("button", { name: "Show or hide the navigation" });
  await expect(toggle).toHaveAttribute("data-expanded", "true");
  await expect(page.getByRole("link", { name: "Teams" })).toBeVisible();

  await toggle.click();
  // Collapsed to icon-only: the leaf keeps its accessible name via aria-label, so the same
  // by-name locator still finds it.
  await expect(toggle).not.toHaveAttribute("data-expanded", "true");
  await expect(page.getByRole("link", { name: "Teams" })).toBeVisible();

  await page.reload();
  await expect(toggle).not.toHaveAttribute("data-expanded", "true");

  await toggle.click();
  await expect(toggle).toHaveAttribute("data-expanded", "true");
});

test("switching the theme in the account menu updates the color scheme and persists across reload", async ({ page }) => {
  await login(page);
  const html = page.locator("html");

  await accountMenu(page).click();
  await page.getByText("Dark", { exact: true }).click();
  await expect(html).toHaveAttribute("data-mantine-color-scheme", "dark");

  await page.keyboard.press("Escape");
  await page.reload();
  await expect(html).toHaveAttribute("data-mantine-color-scheme", "dark");

  await accountMenu(page).click();
  await page.getByText("Light", { exact: true }).click();
  await expect(html).toHaveAttribute("data-mantine-color-scheme", "light");
});
