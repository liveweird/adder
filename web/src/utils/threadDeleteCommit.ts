import type { QueryClient } from "@tanstack/react-query";
import { createDeleteCommitTracker, type DeleteCommit } from "./deleteCommitTracker";
import type { LiveServerFrame } from "./liveProtocol";
import { activeQueriesAreFresh } from "./queryRefresh";
import { roomKeys } from "./roomKeys";
import {
  deletedThreadDependencies,
  deletedThreadReliabilityKeys,
  resetDeletedThreadContent,
  threadLifecycleKeys,
} from "./threadLifecycleCache";

type DeleteTarget = { roomId: number; threadNumber: number };
export type ThreadDeleteCommit = DeleteCommit;
const idOf = ({ roomId, threadNumber }: DeleteTarget) => `${roomId}:${threadNumber}`;

function deleteFrameTarget(frame: LiveServerFrame): DeleteTarget | null {
  return frame.type === "event" && frame.kind === "thread.deleted" && frame.threadNumber !== undefined
    ? { roomId: frame.roomId, threadNumber: frame.threadNumber }
    : null;
}

function refreshThreadDelete(
  queryClient: QueryClient,
  target: DeleteTarget,
  frame: LiveServerFrame,
  sessionIsValid: () => boolean,
): Promise<boolean> {
  const dependencies = deletedThreadDependencies(queryClient, frame);
  const reliability = deletedThreadReliabilityKeys(queryClient, target.roomId);
  const aggregates = threadLifecycleKeys(target.roomId, true)
    .filter((queryKey) => queryKey[1] !== roomKeys.pins(target.roomId)[1]);

  return resetDeletedThreadContent(queryClient, target.roomId, dependencies)
    .then(async () => {
      if (!sessionIsValid()) return [];
      await Promise.all(aggregates.map((queryKey) => queryClient.cancelQueries({ queryKey })));
      return sessionIsValid()
        ? Promise.all(aggregates.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
        : [];
    })
    .then(() => sessionIsValid() && activeQueriesAreFresh(queryClient, ...aggregates, ...dependencies, ...reliability))
    .catch(() => false);
}

const tracker = createDeleteCommitTracker<DeleteTarget>({
  idOf,
  targetFromFrame: deleteFrameTarget,
  frameFromTarget: (target) => ({ type: "event", kind: "thread.deleted", ...target }),
  refresh: refreshThreadDelete,
});

/** Register before DELETE starts; registration alone never changes cached content. */
export function beginThreadDeleteCommit(queryClient: QueryClient, target: DeleteTarget): ThreadDeleteCommit {
  return tracker.begin(queryClient, target);
}

/** Consume only an exact terminal hint for a delete begun in this QueryClient. */
export function commitTrackedThreadDelete(queryClient: QueryClient, frame: LiveServerFrame, replay: () => void): boolean {
  return tracker.commit(queryClient, frame, replay);
}

/** A logical session boundary must not retain proof from the previous caller. */
export function clearThreadDeleteCommits(queryClient: QueryClient): void {
  tracker.clear(queryClient);
}
