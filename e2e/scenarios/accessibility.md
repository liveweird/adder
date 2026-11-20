# Accessibility smoke (axe, WCAG A/AA)

- **Spec**: [tests/accessibility.spec.ts](../tests/accessibility.spec.ts)
- **Actors**: the seed administrator (`admin@adder.local`) for the authenticated pages; an
  anonymous visitor for the login screen
- **Owns** (exclusive server-side state): one fixture team (unique `e2e-axe-*` name), created
  through the API before the detail-page block and deleted after it, and one fixture room
  (unique `e2e-axe-room-*` name, a copy of the seeded Default template, the admin its owner) with one thread in it
  (`e2e-axe-thread-*`, started through the API; threads are never deleted, the room's deletion hides it) which is pinned together with its
  opening message through the API (the pinned panel's content; the pins go with the room), and an archived twin of it
  (for the read-only workflow page), created and deleted the same way;
  the list/form pages and the login screen are read-only

This is the registered template-title exception (see README.md): one `test()` per page is
generated from a list, so a single scenario section stands in for each list.

## Scenario: login screen has no WCAG A/AA violations

1. An anonymous visitor opens `/login` and waits for the sign-in form.
   - *Expected*: an axe scan (WCAG 2.0/2.1 A+AA, `color-contrast` included) reports zero
     violations.

## Scenario: `<path>` has no WCAG A/AA violations

1. The admin signs in, opens `<path>`, and waits for its heading.
   - *Expected*: an axe scan (same tags) reports zero violations.

The list covers the Home page, the Rooms list and its create form, the Teams list, the Users list and its create form, the workflow-template list and its create form, the ADMIN feature-flags screen,
Change password, Notification emails and the Changelog — see `tests/accessibility.spec.ts`'s `AUTHED_PAGES` list for the current set. The fixture room's own
pages are scanned by the detail-page block, each as its own test (`the room home page (the thread list)`, `the room's thread view`,
`the thread's side panel`, `the thread's details editor`, `the new-thread form`, the members, workflow and settings pages): the thread list and the three-pane thread view are scanned only
once both the list and the pane have loaded; the side panel is scanned open (below a wide main area through its **Details** toggle: status select, owner picker, details, history) and again with the details editor open.
The 0.6.0 overlays are scanned scoped to their dialog, once the dialog has settled at full opacity: the header's notification drawer (opened from the bell; the
admin has no notices, so it shows its empty state) and the fixture room's pinned panel (opened from **Pinned (n)**, listing the pinned thread and the pinned message).

## Scenario: `<path>` has no WCAG A/AA violations in the dark scheme

1. The browser context emulates the dark colour scheme (Playwright `colorScheme: "dark"`; the
   app's `auto` default follows it). The admin signs in, opens `<path>` (the same list as above)
   and waits for its heading.
   - *Expected*: `<html>` carries `data-mantine-color-scheme="dark"` — the scan really runs in the
     dark palette — and an axe scan (same tags, `color-contrast` included) reports zero violations.

## Scenario: login screen has no WCAG A/AA violations in the dark scheme

1. An anonymous visitor with the dark scheme emulated opens `/login` and waits for the sign-in form.
   - *Expected*: the dark scheme is on `<html>` and the axe scan reports zero violations.

## Scenario: the app shell at a mobile width has no WCAG A/AA violations

1. The browser viewport is 390x844. The admin signs in, opens `/` and waits for the Home heading.
   - *Expected*: the header's burger is visible with the accessible name "Toggle navigation" (it is
     the only control that opens the nav drawer at this width), `<html>` carries the light scheme
     and an axe scan (same tags, `color-contrast` included) reports zero violations.

## Scenario: the app shell at a mobile width has no WCAG A/AA violations in the dark scheme

1. The same at 390x844 with the dark scheme emulated.
   - *Expected*: the burger is visible by name, `<html>` carries the dark scheme and the axe scan
     reports zero violations.

## Not covered here (and why)

- **Interactive journeys mid-flight** (the one-time password reveal, a confirm mid-transition)
  — those need the journey that produces them; covered by their own journey specs where
  reachable through the ordinary flow.
- **The opened mobile nav drawer** — only the closed shell (header + page) is scanned at the phone
  width; the drawer's slide-in transition makes contrast measurement racy.
- **Modals and detail pages in the dark scheme** — the dark pass covers the page sets above, not the
  overlay or the fixture team's and room's detail pages.
- **Colour tokens themselves** — the ratios are pinned in `web/src/theme.test.ts`; axe here
  verifies the rendered pages honour them.
