import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { jsonResponse } from "../test/http";
import { cachedLinkPreview, clearRoomLinkPreviews, loadLinkPreview } from "./linkPreviewCache";

const source = { roomId: 12, messageId: 23, editedAt: null };
const url = "https://docs.example.com/replicas";
const response = { kind: "PAGE", url, finalUrl: url, title: "Replica notes", image: null };
const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  localStorage.setItem("adder.auth.token", "test-token");
  fetchMock.mockImplementation(async () => jsonResponse(200, response));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  localStorage.clear();
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

test("reads only fresh cache entries for the same session, source and content version", async () => {
  const client = new QueryClient();
  const now = vi.spyOn(Date, "now").mockReturnValue(100);
  expect(cachedLinkPreview(client, source, url, "alice")).toBeUndefined();
  await loadLinkPreview(client, source, url, "alice");
  expect(cachedLinkPreview(client, source, url, "alice")).toMatchObject(response);
  expect(cachedLinkPreview(client, source, url, "bob")).toBeUndefined();
  expect(cachedLinkPreview(client, { ...source, editedAt: 200 }, url, "alice")).toBeUndefined();
  now.mockReturnValue(300_101);
  expect(cachedLinkPreview(client, source, url, "alice")).toBeUndefined();
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

test("bounds retained results and removes only the preview entries in a revoked room", async () => {
  const client = new QueryClient();
  const now = vi.spyOn(Date, "now");
  const otherRoom = { ...source, roomId: 42 };
  for (let index = 0; index < 33; index += 1) {
    now.mockReturnValue(100 + index);
    await loadLinkPreview(client, source, `${url}/${index}`, "alice");
  }
  expect(client.getQueryCache().getAll()).toHaveLength(32);
  expect(cachedLinkPreview(client, source, `${url}/0`, "alice")).toBeUndefined();
  expect(cachedLinkPreview(client, source, `${url}/32`, "alice")).toBeDefined();
  await loadLinkPreview(client, otherRoom, url, "alice");
  clearRoomLinkPreviews(client, source.roomId);
  expect(cachedLinkPreview(client, source, `${url}/32`, "alice")).toBeUndefined();
  expect(cachedLinkPreview(client, otherRoom, url, "alice")).toBeDefined();
});

test("removing a revoked room aborts an outstanding preview", async () => {
  const client = new QueryClient();
  let signal: AbortSignal | null | undefined;
  fetchMock.mockImplementationOnce((_input, init) => {
    signal = init?.signal;
    return new Promise<Response>((_resolve, reject) => {
      signal?.addEventListener("abort", () => reject(signal?.reason), { once: true });
    });
  });
  const loading = loadLinkPreview(client, source, url, "alice");
  expect(signal?.aborted).toBe(false);
  const rejected = expect(loading).rejects.toThrow();
  clearRoomLinkPreviews(client, source.roomId);
  await rejected;
  expect(signal?.aborted).toBe(true);
  expect(cachedLinkPreview(client, source, url, "alice")).toBeUndefined();
});

test("loads previews when the runtime has no AbortSignal timeout helper", async () => {
  const timeout = AbortSignal.timeout;
  Object.defineProperty(AbortSignal, "timeout", { configurable: true, value: undefined });
  try {
    const client = new QueryClient();
    await expect(loadLinkPreview(client, source, url, "alice")).resolves.toMatchObject(response);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  } finally {
    Object.defineProperty(AbortSignal, "timeout", { configurable: true, value: timeout });
  }
});

test("fails a rejected preview request safely when Web Locks are unavailable", async () => {
  fetchMock.mockResolvedValueOnce(jsonResponse(401, { title: "Unauthorized", status: 401 }));
  vi.stubGlobal("navigator", undefined);
  const client = new QueryClient();
  await expect(loadLinkPreview(client, source, url, "alice")).rejects.toMatchObject({ status: 401 });
  expect(cachedLinkPreview(client, source, url, "alice")).toBeUndefined();
});
