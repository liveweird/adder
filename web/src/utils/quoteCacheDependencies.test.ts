import { describe, expect, test } from "vitest";
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { quoteDependencyKeys } from "./quoteCacheDependencies";
import type { LiveServerFrame } from "./liveProtocol";

const quote = (roomId: number, threadNumber: number, messageId: number) => ({ roomId, threadNumber, messageId });
const event = (kind: string, roomId: number, threadNumber?: number, messageId?: number): LiveServerFrame => ({
  type: "event", kind, roomId, ...(threadNumber === undefined ? {} : { threadNumber }), ...(messageId === undefined ? {} : { messageId }),
});

describe("quote cache dependencies", () => {
  test("finds only destination message and pin caches that contain the changed source", () => {
    const client = new QueryClient();
    const first = ["rooms", "threads", 12, "messages", 42] as const;
    const second = ["rooms", "threads", 13, "messages", 9] as const;
    const pins = ["rooms", "pins", 14] as const;
    client.setQueryData(first, { pages: [{ items: [{ id: 1, quote: quote(5, 7, 99) }] }] });
    client.setQueryData(second, { pages: [{ items: [{ id: 2, quote: quote(5, 7, 100) }] }] });
    client.setQueryData(pins, { items: [{ message: { id: 3, quote: quote(5, 8, 99) } }] });
    client.setQueryData(["rooms", "detail", 12], { quote: quote(5, 7, 99) });

    expect(quoteDependencyKeys(client, event("message.edited", 5, 7, 99))).toEqual([first, pins]);
    expect(quoteDependencyKeys(client, event("thread.deleted", 5, 7))).toEqual([first, second, pins]);
    expect(quoteDependencyKeys(client, { type: "revoked", roomId: 5 })).toEqual([first, second, pins]);
  });

  test("revocation includes pending foreign message and pin reads whose first body cannot be inspected yet", () => {
    const client = new QueryClient();
    const message = ["rooms", "threads", 12, "messages", 42] as const;
    const pins = ["rooms", "pins", 14] as const;
    const sourcePins = ["rooms", "pins", 5] as const;
    const pending = () => new Promise<never>(() => undefined);
    const observers = [message, pins, sourcePins].map((queryKey) => new QueryObserver(client, { queryKey, queryFn: pending }));
    const detach = observers.map((observer) => observer.subscribe(() => undefined));

    expect(quoteDependencyKeys(client, { type: "revoked", roomId: 5 })).toEqual([message, pins]);

    detach.forEach((unsubscribe) => unsubscribe());
    client.clear();
  });
});
