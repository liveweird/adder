import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { within } from "@testing-library/react";
import type { NotificationFeedItem } from "../api/notifications";
import i18n from "../i18n";
import enNotifications from "../locales/en/notifications.json";
import { slot } from "../utils/slots";
import { renderWithProviders, screen } from "../test/render";
import NoticeRow from "./NoticeRow";

const notice = (over: Partial<NotificationFeedItem> = {}): NotificationFeedItem => ({
  id: 77,
  type: "MENTIONED",
  seen: false,
  createdAt: 1_795_000_000_000,
  actorId: 7,
  actorName: "Ada",
  roomId: 12,
  roomName: "Payments outage",
  threadNumber: 42,
  threadTitle: "Awaria bazy",
  messageId: 991,
  ...over,
});

const renderRow = (n: NotificationFeedItem, onOpen = vi.fn()) => renderWithProviders(<ul><NoticeRow n={n} zone="UTC" onOpen={onOpen} /></ul>);

describe("NoticeRow", () => {
  test("three links, none inside another: the actor, the notice (named for the whole row, its text the rest of the sentence) and the room", () => {
    renderRow(notice());
    const row = screen.getByRole("listitem");
    const links = within(row).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("href"))).toEqual(["/people/7", "/rooms/12/threads/42?message=991", "/rooms/12"]);
    expect(row.querySelector("a a")).toBeNull();
    // The notice link's text is what follows the actor; its name is the row's text, unseen word first (the visible words are inside the name).
    expect(links[1]).toHaveTextContent(/^\s*mentioned you in #42 Awaria bazy$/);
    expect(links[1]).toHaveAccessibleName("Unseen Ada mentioned you in #42 Awaria bazy");
    expect(links[0]).toHaveAccessibleName("Ada");
    expect(links[2]).toHaveAccessibleName("Payments outage");
  });

  test("the notice link is the only one that asks the drawer to act on the notice", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn((_n: NotificationFeedItem, event: React.MouseEvent) => event.preventDefault());
    renderRow(notice(), onOpen);
    await user.click(screen.getByRole("link", { name: "Ada" }));
    await user.click(screen.getByRole("link", { name: "Payments outage" }));
    expect(onOpen).not.toHaveBeenCalled();
    await user.click(screen.getByRole("link", { name: /^Unseen Ada mentioned you/ }));
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  test("a room membership notice names who added me and offers a visible, accessible action to open the room", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn((_n: NotificationFeedItem, event: React.MouseEvent) => event.preventDefault());
    renderRow(notice({ type: "ROOM_ADDED", threadNumber: null, threadTitle: null, messageId: null }), onOpen);
    const row = screen.getByRole("listitem");
    const links = within(row).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("href"))).toEqual(["/people/7", "/rooms/12"]);
    const open = within(row).getByRole("link", { name: "Unseen Ada added you to the room. Open room" });
    expect(open).toHaveTextContent("added you to the room · Open room");
    await user.click(open);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  test("a reply group is one actor-free action with the grouped count", () => {
    renderRow(notice({ type: "THREAD_REPLIED", replyCount: 12, replyFloorId: 79, replyCeilingId: 90 }));
    const row = screen.getByRole("listitem");
    expect(within(row).queryByRole("link", { name: "Ada" })).not.toBeInTheDocument();
    expect(within(row).getByRole("link", { name: "Unseen 12 new replies in #42 Awaria bazy" })).toHaveAttribute(
      "href",
      "/rooms/12/threads/42?message=991",
    );
  });

  test("a name with markup in it is text, in the sentence and in the link's name", () => {
    renderRow(notice({ actorName: "<b>Ada</b>", threadTitle: "<strong>x</strong>", seen: true }));
    expect(document.querySelector("b, strong")).toBeNull();
    expect(screen.getByRole("link", { name: "<b>Ada</b>" })).toHaveAttribute("href", "/people/7");
    expect(screen.getByRole("link", { name: /^<b>Ada<\/b> mentioned you in #42 <strong>x<\/strong>/ })).toBeInTheDocument();
  });

  test("a seen row has no Unseen word and stays dimmed: the notice link and the names in it take the row's colour", () => {
    renderRow(notice({ seen: true }));
    const [actor, open, room] = screen.getAllByRole("link");
    expect(open.getAttribute("aria-label")).toBe("Ada mentioned you in #42 Awaria bazy");
    expect(open.getAttribute("aria-label")).not.toMatch(/Unseen/);
    for (const link of [actor, open, room]) expect(link.style.color).toBe("inherit");
  });

  // The row cuts the sentence at the actor's marker: a key that lost or doubled `{{actor}}`, or ended right after it, would draw a notice with no
  // actor link, two, or an empty notice link. Every kind, in both languages.
  test.each(["en", "pl"] as const)("every notifications.event key in %s holds the actor once, with words after it", (lng) => {
    const t = i18n.getFixedT(lng);
    const kinds = Object.keys(enNotifications.event);
    expect(kinds).toHaveLength(13);
    for (const kind of kinds) {
      const sentence = t(`notifications.event.${kind as keyof typeof enNotifications.event}`, { actor: slot("actor"), number: 42, title: "T" });
      const parts = sentence.split(slot("actor"));
      expect(parts, `${lng} ${kind}`).toHaveLength(2);
      expect(parts[1].trim().length, `${lng} ${kind} text after the actor`).toBeGreaterThan(0);
    }
  });

  test("a kind this build does not know shows its raw name with no actor link", () => {
    renderRow(notice({ type: "FUTURE_KIND" as never }));
    expect(screen.getByText("FUTURE_KIND")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Ada" })).not.toBeInTheDocument();
  });
});
