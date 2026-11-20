# Accessibility smoke (axe, WCAG A/AA)

- **Spec**: [tests/accessibility.spec.ts](../tests/accessibility.spec.ts)
- **Actors**: four throwaway administrators (`e2e-axe-admin-*`, `specAdmins`: minted through the seed administrator's API
  in a file-level `beforeAll` and deleted last) for the authenticated pages, taken in turn, so the ~90 page loads (each signs in
  straight onto the page it scans, one load a test) spend THEIR per-user `live-ticket` buckets and not the seed
  administrator's; the first is the fixture room's owner, the other three are plain members of it and read the pages in turn, and the pages whose scanned state depends on the reader (a member's unread marks and role, the first one's own row, messages and reactions: the room home, the thread views, the members page, the user pages, the notification drawer, the unread marker) are opened by the first, so a page is always opened by the same account; an anonymous visitor for
  the login screen
- **Owns** (exclusive server-side state): one fixture team (unique `e2e-axe-*` name), created
  through the API before the detail-page block and deleted after it, and one fixture room
  (unique `e2e-axe-room-*` name, a copy of the seeded Default template, the first throwaway administrator its owner and the other three its members) with one thread in it
  (`e2e-axe-thread-*`, started through the API; threads are never deleted, the room's deletion hides it) which is pinned together with its
  opening message through the API (the pinned panel's content; the pins go with the room), an archived twin of it
  (for the read-only workflow page), and one fixture teammate (a plain user, unique `e2e-axe-mate-*` name, a member of the fixture room, deleted
  after the rooms) whose messages, reactions and later post fill the conversation, all created and deleted the same way;
  the list/form pages, the login screen and the seeded Default template's edit page (opened, never saved) are read-only

This is the registered template-title exception (see README.md): one `test()` per page is
generated from a list, so a single scenario section stands in for each list.

## Scenario: login screen has no WCAG A/AA violations

1. An anonymous visitor opens `/login` and waits for the sign-in form.
   - *Expected*: an axe scan (WCAG 2.0/2.1 A+AA, `color-contrast` included) reports zero
     violations.

## Scenario: `<path>` has no WCAG A/AA violations

1. The admin signs in, opens `<path>`, and waits for its heading.
   - *Expected*: an axe scan (same tags) reports zero violations.

The list covers the Home page, the Search page (its empty form), the Rooms list and its create form, the Teams list, the Users list and its create form, the workflow-template list and its create form, the ADMIN feature-flags screen,
Change password, Notification emails and the Changelog — see `tests/accessibility.spec.ts`'s `AUTHED_PAGES` list for the current set. The fixture room's own
pages are scanned by the detail-page block, each as its own test (`the room home page (the thread list)`, `the room's thread view`,
`the thread's side panel`, `the thread's details editor`, `the new-thread form`, the members, workflow and settings pages): the thread list and the three-pane thread view are scanned only
once both the list and the pane have loaded; the side panel is scanned open (below a wide main area through its **Details** toggle: status select, owner picker, details, history) and again with the details editor open.
The 0.6.0 overlays are scanned scoped to their dialog, once the dialog has settled at full opacity: the header's notification drawer (opened from the bell; the
teammate's message mentions the admin, so it lists that notice) and the fixture room's pinned panel (opened from **Pinned (n)**, listing the pinned thread and the pinned message).

## Scenario: the reset-password page has no WCAG A/AA violations

1. An anonymous visitor opens `/reset-password` and waits for the "Reset password" heading.
   - *Expected*: an axe scan (same tags) reports zero violations.
2. The visitor opens `/reset-password/confirm` with a well-formed fragment token (nothing is submitted, so nothing is consumed) and waits for the "Set new password" button.
   - *Expected*: the axe scan reports zero violations.

## Scenario: the not-found page has no WCAG A/AA violations

1. The admin signs in and opens a path that matches no route (a unique `nowhere` segment) and waits for the first heading.
   - *Expected*: an axe scan (same tags) reports zero violations.

## Scenario: the room leave dialog has no WCAG A/AA violations

1. The admin signs in and opens the fixture room's members page, then clicks **Leave room** (every sweep administrator is a member of the fixture room, so the button is there; the dialog is only opened, never confirmed).
   - *Expected*: the dialog settles at full opacity with the heading "Leave this room?" and an axe scan scoped to the dialog reports zero violations.

## Scenario: the notification drawer has no WCAG A/AA violations

1. The admin signs in, opens `/` and clicks the header's **Notifications (n)** bell.
   - *Expected*: the drawer settles at full opacity with the heading "Notifications" and the empty state "You have no notifications."; an axe scan scoped to the dialog reports zero violations.

## Scenario: the pinned panel has no WCAG A/AA violations

1. The admin signs in, opens the fixture room and clicks **Pinned (n)**.
   - *Expected*: the panel settles at full opacity, lists the pinned thread (`#<number> <title>`) and the pinned message, and an axe scan scoped to the dialog reports zero violations.

## Scenario: a team editor modal has no WCAG A/AA violations

1. The admin signs in, opens `/teams` and clicks **New team**.
   - *Expected*: the modal settles at full opacity with the heading "New team" and an axe scan scoped to the dialog reports zero violations.

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

## Scenario: `<state>` has no WCAG A/AA violations

1. The admin signs in, opens the state's address (seeding what it shows first, where it says so), and waits for its marker.
   - *Expected*: an axe scan (same tags) reports zero violations; an overlay is scanned scoped to its dialog once it has settled at full opacity.

The states (the detail-page block's list, `DETAIL_PAGES`, plus the overlays and the anonymous pages after it):

- the fixture team's page; the room's pages: home (the thread list), the thread view, the side panel, the details editor, the new-thread form, members, workflow, settings, and the archived twin's read-only workflow page;
- **the thread list's open filter panel** (the **Filters** toggle expanded: statuses, hashtags, owner, "Mine", "Mentions me");
- **the conversation with messages, reactions and pins**: the teammate's Markdown message (bold, link, inline code) carrying a 👍 from both the teammate and the admin (a pressed chip, "2 reactions") and a 🎉, the teammate's message that mentions the admin (a list, a code block, a hashtag), the admin's reply, and the thread and its opening message pinned;
- **the mention picker**: an `@` typed in the composer opens the "People in this room" listbox, scanned with the list showing;
- **the conversation with the new-messages marker**: the teammate posts through the API just before the admin opens the thread, so the "New messages" separator is on screen when the scan runs;
- **the room's search results** and **the search results across rooms**: a search for the fixture thread's unique title lists it (the room's workflow pill in the room's search; the Open pill and the room's name across rooms);
- **the workflow template's edit page**: the seeded Default template, opened and scanned with its fields showing, never saved;
- the edit-user and user-features pages, the reset-password page and its confirm page, the not-found page; the room's "Leave this room?" dialog, the notification drawer, the pinned panel and a team editor modal (each scoped to its dialog).

## Scenario: `<state>` has no WCAG A/AA violations in the dark scheme

1. The same, with the dark colour scheme emulated (Playwright `colorScheme: "dark"`).
   - *Expected*: `<html>` carries the dark scheme before the scan, and the axe scan (same tags, `color-contrast` included) reports zero violations.

## Not covered here (and why)

- **Interactive journeys mid-flight** (the one-time password reveal, a confirm mid-transition)
  — those need the journey that produces them; covered by their own journey specs where
  reachable through the ordinary flow.
- **The opened mobile nav drawer** — only the closed shell (header + page) is scanned at the phone
  width; the drawer's slide-in transition makes contrast measurement racy.
- **Hover, focus and open-menu states** — a message's kebab menu, the emoji picker and a Select's open
  list are not scanned; only the states listed above are (the mention picker is the one popover).
- **Colour tokens themselves** — the ratios are pinned in `web/src/theme.test.ts`; axe here
  verifies the rendered pages honour them.
