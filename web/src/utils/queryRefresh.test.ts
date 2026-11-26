import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { expect, test, vi } from "vitest";
import { activeQueriesAreFresh, refreshQueriesAfterMutation } from "./queryRefresh";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

test("refreshes active initial reads across prefixes, invalidates inactive data and leaves unrelated reads intact", async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  const oldTeams = deferred<string>();
  const oldSystems = deferred<string>();
  const user = deferred<string>();
  const teams = vi.fn().mockImplementationOnce(() => oldTeams.promise).mockResolvedValue("new teams");
  const systems = vi.fn().mockImplementationOnce(() => oldSystems.promise).mockResolvedValue("new systems");
  const users = vi.fn(() => user.promise);
  const subscriptions = [
    new QueryObserver(client, { queryKey: ["teams", "list"], queryFn: teams }).subscribe(() => undefined),
    new QueryObserver(client, { queryKey: ["systems", "list"], queryFn: systems }).subscribe(() => undefined),
    new QueryObserver(client, { queryKey: ["users", "list"], queryFn: users }).subscribe(() => undefined),
  ];
  client.setQueryData(["systems", "detail", 7], "cached system");
  try {
    await vi.waitFor(() => { expect(teams).toHaveBeenCalledTimes(1); expect(systems).toHaveBeenCalledTimes(1); });
    await refreshQueriesAfterMutation(client, ["teams"], ["systems"]);
    expect(teams).toHaveBeenCalledTimes(2);
    expect(systems).toHaveBeenCalledTimes(2);
    expect(client.getQueryData(["teams", "list"])).toBe("new teams");
    expect(client.getQueryData(["systems", "list"])).toBe("new systems");
    expect(client.getQueryState(["systems", "detail", 7])?.isInvalidated).toBe(true);
    expect(client.getQueryData(["systems", "detail", 7])).toBe("cached system");
    expect(users).toHaveBeenCalledTimes(1);
    expect(client.getQueryState(["users", "list"])?.fetchStatus).toBe("fetching");
    oldTeams.resolve("old teams");
    oldSystems.resolve("old systems");
    user.resolve("user result");
    await vi.waitFor(() => expect(client.getQueryData(["users", "list"])).toBe("user result"));
    expect(client.getQueryData(["teams", "list"])).toBe("new teams");
    expect(client.getQueryData(["systems", "list"])).toBe("new systems");
  } finally {
    oldTeams.resolve("old teams");
    oldSystems.resolve("old systems");
    user.resolve("user result");
    subscriptions.forEach((unsubscribe) => unsubscribe());
    client.clear();
  }
});

test("coverage requires every active matching query to be successful, idle and fresh", async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const root = ["rooms", "threads", 12, "messages", 4];
  const timeline = new QueryObserver(client, { queryKey: [...root, "timeline"], queryFn: () => "fresh" });
  const roster = new QueryObserver<string>(client, {
    queryKey: [...root, "reactors"],
    queryFn: () => Promise.reject(new Error("offline")),
  });
  const unsubscribers = [timeline.subscribe(() => undefined), roster.subscribe(() => undefined)];
  try {
    await vi.waitFor(() => expect(client.isFetching({ queryKey: root })).toBe(0));
    expect(activeQueriesAreFresh(client, root)).toBe(false);
    roster.setOptions({ queryKey: [...root, "reactors"], queryFn: () => "fresh roster" });
    await roster.refetch();
    expect(activeQueriesAreFresh(client, root)).toBe(true);
    client.setQueryData([...root, "inactive"], "stale cache");
    await client.invalidateQueries({ queryKey: [...root, "inactive"], refetchType: "none" });
    expect(activeQueriesAreFresh(client, root)).toBe(true);
  } finally {
    unsubscribers.forEach((unsubscribe) => unsubscribe());
    client.clear();
  }
});

test("an active invalidated or still-fetching query cannot cover a mutation hint", async () => {
  const client = new QueryClient();
  const root = ["rooms", "threads", 12, "messages", 4];
  const key = [...root, "timeline"];
  const next = deferred<string>();
  const observer = new QueryObserver(client, { queryKey: key, queryFn: () => "initial timeline" });
  const unsubscribe = observer.subscribe(() => undefined);
  try {
    await observer.refetch();
    expect(activeQueriesAreFresh(client, root)).toBe(true);

    await client.invalidateQueries({ queryKey: key, refetchType: "none" });
    expect(client.getQueryState(key)?.fetchStatus).toBe("idle");
    expect(activeQueriesAreFresh(client, root)).toBe(false);

    observer.setOptions({ queryKey: key, queryFn: () => next.promise });
    const refetch = observer.refetch();
    await vi.waitFor(() => expect(client.getQueryState(key)?.fetchStatus).toBe("fetching"));
    expect(activeQueriesAreFresh(client, root)).toBe(false);
    next.resolve("fresh timeline");
    await refetch;
    expect(activeQueriesAreFresh(client, root)).toBe(true);
  } finally {
    next.resolve("fresh timeline");
    unsubscribe();
    client.clear();
  }
});
