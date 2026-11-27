import type { QueryClient } from "@tanstack/react-query";
import type { LiveServerFrame } from "./liveProtocol";

type Replay = () => void;

interface Operation<Target> {
  target: Target;
  refresh: Promise<boolean> | null;
  committed: boolean;
  session: { valid: boolean };
  expires: ReturnType<typeof setTimeout>;
}

interface Registry<Target> {
  operations: Map<string, Operation<Target>>;
  session: { valid: boolean };
}

export interface DeleteCommit {
  /** HTTP success is authoritative even when the matching live hint has not arrived yet. */
  confirm(): Promise<void>;
  /** Whether a failed response is already settled by live proof, or belongs to an ended logical session. */
  reject(): Promise<boolean>;
}

interface DeleteCommitTrackerOptions<Target> {
  idOf(target: Target): string;
  targetFromFrame(frame: LiveServerFrame): Target | null;
  frameFromTarget(target: Target): LiveServerFrame;
  refresh(queryClient: QueryClient, target: Target, frame: LiveServerFrame, sessionIsValid: () => boolean): Promise<boolean>;
}

export interface DeleteCommitTracker<Target> {
  begin(queryClient: QueryClient, target: Target): DeleteCommit;
  commit(queryClient: QueryClient, frame: LiveServerFrame, replay: Replay): boolean;
  clear(queryClient: QueryClient): void;
}

const RETAIN_MS = 30_000;
const MAX_TRACKED = 32;

/** Correlates an in-flight DELETE with its exact terminal live hint; resource-specific refresh policy stays with the caller. */
export function createDeleteCommitTracker<Target>(options: DeleteCommitTrackerOptions<Target>): DeleteCommitTracker<Target> {
  const tracked = new WeakMap<QueryClient, Registry<Target>>();

  function remove(queryClient: QueryClient, operation: Operation<Target>): void {
    const registry = tracked.get(queryClient);
    const id = options.idOf(operation.target);
    if (registry?.operations.get(id) !== operation) return;
    clearTimeout(operation.expires);
    registry.operations.delete(id);
  }

  function startRefresh(queryClient: QueryClient, operation: Operation<Target>, frame: LiveServerFrame): Promise<boolean> {
    if (operation.refresh !== null) return operation.refresh;
    if (!operation.session.valid) return Promise.resolve(false);
    operation.committed = true;
    operation.refresh = options.refresh(queryClient, operation.target, frame, () => operation.session.valid);
    return operation.refresh;
  }

  function begin(queryClient: QueryClient, target: Target): DeleteCommit {
    let registry = tracked.get(queryClient);
    if (registry === undefined) {
      registry = { operations: new Map(), session: { valid: true } };
      tracked.set(queryClient, registry);
    }
    const id = options.idOf(target);
    const previous = registry.operations.get(id);
    if (previous !== undefined) remove(queryClient, previous);
    if (registry.operations.size >= MAX_TRACKED) {
      remove(queryClient, registry.operations.values().next().value as Operation<Target>);
    }
    const operation: Operation<Target> = {
      target,
      refresh: null,
      committed: false,
      session: registry.session,
      expires: setTimeout(() => remove(queryClient, operation), RETAIN_MS),
    };
    registry.operations.set(id, operation);
    return {
      confirm: () => startRefresh(queryClient, operation, options.frameFromTarget(target)).then(() => undefined),
      async reject() {
        if (!operation.session.valid) return true;
        if (!operation.committed) {
          remove(queryClient, operation);
          return false;
        }
        await operation.refresh;
        return operation.committed;
      },
    };
  }

  function commit(queryClient: QueryClient, frame: LiveServerFrame, replay: Replay): boolean {
    const target = options.targetFromFrame(frame);
    if (target === null) return false;
    const operation = tracked.get(queryClient)?.operations.get(options.idOf(target));
    if (operation === undefined) return false;
    void startRefresh(queryClient, operation, frame).then((reliable) => {
      if (!reliable && operation.session.valid) {
        remove(queryClient, operation);
        replay();
      }
    });
    return true;
  }

  function clear(queryClient: QueryClient): void {
    const registry = tracked.get(queryClient);
    registry?.operations.forEach((operation) => clearTimeout(operation.expires));
    if (registry !== undefined) registry.session.valid = false;
    tracked.delete(queryClient);
  }

  return { begin, commit, clear };
}
