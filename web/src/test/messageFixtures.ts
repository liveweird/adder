// Shared fixtures for the conversation's tests (the message list, item, editor, composer and the thread pane): one room, one thread, one message.
import type { MessageResponse } from "../api/messages";
import type { RoomResponse } from "../api/rooms";
import type { ThreadResponse } from "../api/threads";

export const SOME_TIME = Date.UTC(2026, 5, 1, 9, 30);

export function makeRoom(overrides: Partial<RoomResponse> = {}): RoomResponse {
  return {
    id: 12,
    name: "Night Shift",
    description: null,
    archived: false,
    archivedAt: null,
    role: "MEMBER",
    memberCount: 3,
    workflowRevision: 1,
    lastActivityAt: 1,
    createdAt: 1,
    updatedAt: 1,
    ...overrides,
  };
}

export function makeThread(overrides: Partial<ThreadResponse> = {}): ThreadResponse {
  return {
    id: 1,
    number: 42,
    title: "Disk full on db-3",
    status: "triage",
    closed: false,
    closedAt: null,
    ownerId: 5,
    ownerName: "Ann Owner",
    createdById: 6,
    createdByName: "Bob Starter",
    fields: {},
    fieldUserNames: {},
    messageCount: 3,
    revision: 1,
    lastActivityAt: SOME_TIME,
    createdAt: SOME_TIME,
    updatedAt: SOME_TIME,
    unreadCount: 0,
    firstUnreadMessageId: null,
    watching: false,
    watcherCount: 1,
    ...overrides,
  };
}

export function makeMessage(overrides: Partial<MessageResponse> = {}): MessageResponse {
  return {
    id: 100,
    threadNumber: 42,
    authorId: 7,
    authorName: "Cy Author",
    body: "Restarted the **replica**.",
    deleted: false,
    deletedByModerator: false,
    editedAt: null,
    createdAt: SOME_TIME,
    mentions: [],
    groupMentions: [],
    reactions: [],
    ...overrides,
  };
}

/** A messages page as the server answers it: newest first, `total` counted after the cursor. */
export function messagePage(items: MessageResponse[], total = items.length) {
  return { items, page: 1, pageSize: 50, total };
}
