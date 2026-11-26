import { useState } from "react";
import { useMutation, QueryClient } from "@tanstack/react-query";
import { act, fireEvent, renderWithProviders, screen, waitFor } from "../test/render";
import { afterEach, describe, expect, test, vi } from "vitest";
import { getSessionSnapshot, getUserId, persistRefreshedSession, persistSession } from "../api/session";
import type { components } from "../api/schema";
import { bindSessionQueryCache } from "../utils/sessionQueryCache";
import SessionBoundary from "./SessionBoundary";

type LoginSuccess = components["schemas"]["LoginResponse"];

function session(userId: number, token: string): LoginSuccess {
  return {
    token,
    expiresAt: 1,
    refreshToken: `refresh-${token}`,
    refreshExpiresAt: 2,
    userId,
    roles: [],
    disabledFeatures: [],
    language: "en",
  };
}

function ResultPanel({ request, published }: { request: () => Promise<string>; published: () => void }) {
  const [result, setResult] = useState("empty");
  const mutation = useMutation({
    mutationFn: request,
    onSuccess: (value) => {
      published();
      setResult(value);
    },
  });
  return (
    <div>
      <button onClick={() => mutation.mutate()}>Run</button>
      <span>{getUserId()}:{result}</span>
    </div>
  );
}

describe("session boundary", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  test("switching accounts retires the old account's browser push binding", async () => {
    persistSession(session(1, "token-a"));
    const unsubscribe = vi.fn(async () => true);
    const subscription = { endpoint: "https://push.example/old", unsubscribe };
    const registration = { pushManager: { getSubscription: vi.fn(async () => subscription) } };
    vi.stubGlobal("isSecureContext", true);
    vi.stubGlobal("PushManager", class {});
    vi.stubGlobal("Notification", { permission: "granted" });
    vi.stubGlobal("navigator", {
      ...navigator,
      locks: undefined,
      serviceWorker: { getRegistration: vi.fn(async () => registration) },
    });
    localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 1, id: 41 }));
    renderWithProviders(<SessionBoundary><span>content</span></SessionBoundary>);

    await act(async () => { persistSession(session(2, "token-b")); });

    await waitFor(() => expect(unsubscribe).toHaveBeenCalledOnce());
    expect(localStorage.getItem("adder.browserPush")).toContain('"userId":1');
    expect(localStorage.getItem("adder.browserPush")).toContain('"pending"');
  });

  test("account replacement preserves cleanup when native push is unavailable", async () => {
    persistSession(session(1, "token-a"));
    vi.stubGlobal("isSecureContext", false);
    localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 1, id: 41 }));
    renderWithProviders(<SessionBoundary><span>content</span></SessionBoundary>);

    await act(async () => { persistSession(session(2, "token-b")); });

    await waitFor(() => expect(localStorage.getItem("adder.browserPush")).toContain('"pending"'));
    expect(localStorage.getItem("adder.browserPush")).toContain('"userId":1');
  });

  test("a provider failure while switching accounts preserves the pending cleanup and the new session", async () => {
    persistSession(session(1, "token-a"));
    const unsubscribe = vi.fn(async () => { throw new Error("provider offline"); });
    const subscription = { endpoint: "https://push.example/old", unsubscribe };
    const registration = { pushManager: { getSubscription: vi.fn(async () => subscription) } };
    vi.stubGlobal("isSecureContext", true);
    vi.stubGlobal("PushManager", class {});
    vi.stubGlobal("Notification", { permission: "granted" });
    vi.stubGlobal("navigator", { ...navigator, locks: undefined, serviceWorker: { getRegistration: async () => registration } });
    const prior = { userId: 1, id: 41, bindingId: "browser-session-a" };
    localStorage.setItem("adder.browserPush", JSON.stringify({ active: prior, pending: [prior] }));
    renderWithProviders(<SessionBoundary><span>content</span></SessionBoundary>);

    await act(async () => { persistSession(session(2, "token-b")); });

    await waitFor(() => expect(unsubscribe).toHaveBeenCalledOnce());
    expect(getUserId()).toBe(2);
    expect(screen.getByText("content")).toBeVisible();
    expect(JSON.parse(localStorage.getItem("adder.browserPush")!)).toEqual({ pending: [prior] });
  });


  test("a late mutation cannot show the old account's result after a cross-tab switch", async () => {
    persistSession(session(1, "token-a"));
    let release!: (value: string) => void;
    const response = new Promise<string>((resolve) => { release = resolve; });
    let publications = 0;
    const client = new QueryClient();
    const unbind = bindSessionQueryCache(client);
    try {
      renderWithProviders(
        <SessionBoundary>
          <ResultPanel request={() => response} published={() => { publications += 1; }} />
        </SessionBoundary>,
        { queryClient: client },
      );
      fireEvent.click(screen.getByRole("button", { name: "Run" }));
      expect(screen.getByText("1:empty")).toBeInTheDocument();

      await act(async () => {
        localStorage.setItem("adder.auth.token", "token-b");
        localStorage.setItem("adder.auth.userId", "2");
        localStorage.setItem("adder.auth.sessionIdentity", "external-session-b");
        window.dispatchEvent(new StorageEvent("storage", {
          key: "adder.auth.sessionIdentity",
          newValue: "external-session-b",
          storageArea: localStorage,
        }));
      });
      expect(screen.getByText("2:empty")).toBeInTheDocument();

      await act(async () => { release("A's private result"); });
      await waitFor(() => expect(publications).toBe(1));
      expect(screen.getByText("2:empty")).toBeInTheDocument();
      expect(screen.queryByText(/A's private result/)).not.toBeInTheDocument();
    } finally {
      unbind();
      client.clear();
    }
  });

  test("a new login remounts local state, while token rotation keeps it", async () => {
    persistSession(session(1, "token-a"));
    const unsubscribe = vi.fn(async () => true);
    const subscription = { endpoint: "https://push.example/current", unsubscribe };
    vi.stubGlobal("isSecureContext", true);
    vi.stubGlobal("PushManager", class {});
    vi.stubGlobal("Notification", { permission: "granted" });
    vi.stubGlobal("navigator", {
      ...navigator,
      locks: undefined,
      serviceWorker: {
        getRegistration: vi.fn(async () => ({ pushManager: { getSubscription: vi.fn(async () => subscription) } })),
      },
    });
    localStorage.setItem("adder.browserPush", JSON.stringify({ userId: 1, id: 41 }));
    renderWithProviders(
      <SessionBoundary>
        <ResultPanel request={async () => "saved for A"} published={() => undefined} />
      </SessionBoundary>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    await screen.findByText("1:saved for A");

    const beforeRefresh = getSessionSnapshot();
    await act(async () => {
      expect(persistRefreshedSession(session(1, "token-a2"), beforeRefresh)).not.toBeNull();
    });
    expect(screen.getByText("1:saved for A")).toBeInTheDocument();
    expect(unsubscribe).not.toHaveBeenCalled();

    await act(async () => { persistSession(session(2, "token-b")); });
    expect(screen.getByText("2:empty")).toBeInTheDocument();
  });
});
