import type { NotificationFeedItem } from "../api/notifications";
import { roomPath, threadMessagePath, threadPath } from "./roomLinks";

/** A notice opens the message that made it (a mention or a reply: the 0.5.0 jump), or the thread for a thread-level kind (a hand-over, a status move), which has no message. */
export const noticeLinkOf = (n: Pick<NotificationFeedItem, "roomId" | "threadNumber" | "messageId">) =>
  n.threadNumber === null ? roomPath(n.roomId) : n.messageId === null ? threadPath(n.roomId, n.threadNumber) : threadMessagePath(n.roomId, n.threadNumber, n.messageId);
