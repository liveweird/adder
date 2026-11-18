import type { RoomWorkflow } from "../api/rooms";
import { statusHue } from "../utils/workflowColours";
import StatusPill from "./StatusPill";

/**
 * A thread's status as the room's workflow draws it (its label and colour, with the dot so the colour is never the only carrier). A key
 * the workflow does not list (a read that is older than the thread's last move) shows as a neutral pill with the bare key.
 */
export default function ThreadStatusPill({ statuses, statusKey, size }: { statuses: RoomWorkflow["statuses"] | undefined; statusKey: string; size?: "sm" | "md" }) {
  const status = statuses?.find((candidate) => candidate.key === statusKey);
  return (
    <StatusPill color={status ? statusHue(status.colour) : "gray"} dot size={size}>
      {status?.label ?? statusKey}
    </StatusPill>
  );
}
