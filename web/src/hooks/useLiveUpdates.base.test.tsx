import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { FakeClock, lastSocket, SocketStub } from "../test/socket";
import { useLiveUpdates } from "./useLiveUpdates";

// The build talks to its API on another origin (`VITE_API_BASE`): the socket must open THERE, not on the page's own host.
vi.mock("../api/http", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/http")>()),
  API_BASE: "https://api.example.test",
}));

async function settle() {
  for (let i = 0; i < 6; i++) await Promise.resolve();
}

describe("useLiveUpdates with an absolute VITE_API_BASE", () => {
  beforeEach(() => {
    SocketStub.reset();
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    SocketStub.reset();
    localStorage.clear();
  });

  test("opens its socket on the API's origin, mapped to wss", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const overrides = {
      clock: new FakeClock(),
      mintTicket: vi.fn(() => Promise.resolve({ ticket: "t".repeat(43) })),
      socketFactory: (url: string) => new SocketStub(url),
    };
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/"]}>{children}</MemoryRouter>
      </QueryClientProvider>
    );
    renderHook(() => useLiveUpdates([], overrides), { wrapper });
    await act(settle);
    expect(lastSocket().url).toBe("wss://api.example.test/api/v1/live");
  });
});
