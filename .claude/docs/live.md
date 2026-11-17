# The live channel (approach)

Owns: how open browsers learn about changes — the WebSocket endpoint, its tickets, the frame protocol, the cross-replica fan-out and the bounds.
Not here: what changes (`chat-model.md`). **Status: the approach, decided 2026-11-17 (WebSockets, the user's choice); written as binding rules in
v0.4.** Nothing of it exists yet.

- **Endpoint:** `GET /api/v1/live` (Ktor `WebSockets`). The SPA's bearer lives in `localStorage` and a browser cannot set headers on a WebSocket,
  so `POST /api/v1/live/tickets` (bearer) mints a single-use, short-lived ticket passed as `?ticket=` — the JWT never appears in a URL.
- **Protocol:** JSON frames. Client → `subscribe {roomIds}`, `unsubscribe`, `ping`. Server → `event {kind, roomId, threadId, messageId?, revision}`
  hints and `revoked` when a membership ends. Writes stay REST (OpenAPI conformance, authorization in one place); a hint makes the SPA invalidate
  the matching TanStack Query keys, and a reconnect invalidates every subscribed room (the refetch is the backfill).
- **Fan-out:** every write issues `pg_notify('adder_events', …)` inside its own transaction (delivered only on commit); one dedicated, non-pooled
  R2DBC connection per process `LISTEN`s and routes to the local sockets — the single documented exception to the one bounded pool.
- **Bounds:** sockets per user, subscriptions per socket, frame size, a heartbeat and an idle close; refusals audited.
