import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import type { RoomOrganizationDestination } from "../utils/roomOrganizationDraft";

export type OrganizationDragSource =
  | { kind: "group"; id: string; name: string }
  | { kind: "room"; id: number; name: string };

type DropTarget =
  | { kind: "group"; beforeGroupId: string | null; element: HTMLElement }
  | { kind: "room"; destination: RoomOrganizationDestination; element: HTMLElement };

const UNGROUPED_DATA_VALUE = "ungrouped";
const DRAG_THRESHOLD = 6;
const EDGE_ZONE = 56;

function dataGroupId(element: HTMLElement): string | null {
  return element.dataset.groupId === UNGROUPED_DATA_VALUE ? null : element.dataset.groupId ?? null;
}

function targetAt(source: OrganizationDragSource, x: number, y: number): DropTarget | null {
  const hit = document.elementFromPoint(x, y) as HTMLElement | null;
  if (!hit) return null;
  const group = hit.closest<HTMLElement>("[data-group-id]");
  if (!group) return null;
  const groupId = dataGroupId(group);
  if (source.kind === "group") {
    if (groupId === source.id) return null;
    return { kind: "group", beforeGroupId: groupId, element: group };
  }
  const row = hit.closest<HTMLElement>("[data-room-id]");
  if (!row) {
    return { kind: "room", destination: { groupId, position: { kind: "end" } }, element: group };
  }
  const roomId = Number(row.dataset.roomId);
  if (roomId === source.id) return null;
  const rect = row.getBoundingClientRect();
  return {
    kind: "room",
    destination: { groupId, position: { kind: y < rect.top + rect.height / 2 ? "before" : "after", roomId } },
    element: row,
  };
}

function sameTarget(left: DropTarget | null, right: DropTarget | null): boolean {
  if (left?.kind !== right?.kind) return false;
  if (!left || !right) return left === right;
  if (left.kind === "group" && right.kind === "group") return left.beforeGroupId === right.beforeGroupId;
  if (left.kind === "room" && right.kind === "room") {
    return JSON.stringify(left.destination) === JSON.stringify(right.destination);
  }
  return false;
}

export function useOrganizationDrag({ disabled, resetKey, onGroupDrop, onRoomDrop, onCancel }: {
  disabled: boolean;
  resetKey: string;
  onGroupDrop: (groupId: string, beforeGroupId: string | null) => void;
  onRoomDrop: (roomId: number, destination: RoomOrganizationDestination) => void;
  onCancel: () => void;
}) {
  const [active, setActive] = useState<OrganizationDragSource | null>(null);
  const targetRef = useRef<DropTarget | null>(null);
  const scrollFrameRef = useRef<() => void>(() => undefined);
  const state = useRef<{
    source: OrganizationDragSource;
    pointerId: number;
    startX: number;
    startY: number;
    x: number;
    y: number;
    dragging: boolean;
    handle: HTMLElement;
    frame: number | null;
  } | null>(null);

  const clearTarget = useCallback(() => {
    targetRef.current?.element.removeAttribute("data-drop-target");
    targetRef.current = null;
  }, []);

  const finish = useCallback((commit: boolean) => {
    const current = state.current;
    if (!current) return;
    if (current.frame !== null) cancelAnimationFrame(current.frame);
    current.handle.closest<HTMLElement>("[data-room-id], [data-group-id]")?.removeAttribute("data-drag-source");
    const target = targetRef.current;
    clearTarget();
    state.current = null;
    setActive(null);
    if (current.handle.hasPointerCapture?.(current.pointerId)) current.handle.releasePointerCapture(current.pointerId);
    if (!current.dragging) return;
    if (!commit || !target) {
      onCancel();
      return;
    }
    if (current.source.kind === "group" && target.kind === "group") {
      onGroupDrop(current.source.id, target.beforeGroupId);
    } else if (current.source.kind === "room" && target.kind === "room") {
      onRoomDrop(current.source.id, target.destination);
    }
  }, [clearTarget, onCancel, onGroupDrop, onRoomDrop]);

  const updateTarget = useCallback((source: OrganizationDragSource, x: number, y: number) => {
    const next = targetAt(source, x, y);
    if (sameTarget(targetRef.current, next)) return;
    targetRef.current?.element.removeAttribute("data-drop-target");
    next?.element.setAttribute("data-drop-target", "true");
    targetRef.current = next;
  }, []);

  const scrollFrame = useCallback(() => {
    const current = state.current;
    if (!current?.dragging) return;
    const speed = current.y < EDGE_ZONE
      ? -Math.min(14, (EDGE_ZONE - current.y) / 3)
      : current.y > window.innerHeight - EDGE_ZONE
        ? Math.min(14, (current.y - window.innerHeight + EDGE_ZONE) / 3)
        : 0;
    if (speed !== 0) {
      window.scrollBy({ top: speed });
      updateTarget(current.source, current.x, current.y);
    }
    current.frame = requestAnimationFrame(scrollFrameRef.current);
  }, [updateTarget]);
  useEffect(() => {
    scrollFrameRef.current = scrollFrame;
  }, [scrollFrame]);

  const handlers = useCallback((source: OrganizationDragSource) => ({
    onPointerDown: (event: ReactPointerEvent<HTMLButtonElement>) => {
      if (disabled || event.button !== 0 || event.isPrimary === false || state.current) return;
      const handle = event.currentTarget;
      state.current = {
        source, pointerId: event.pointerId, startX: event.clientX, startY: event.clientY,
        x: event.clientX, y: event.clientY, dragging: false, handle, frame: null,
      };
      handle.setPointerCapture(event.pointerId);
    },
    onPointerMove: (event: ReactPointerEvent<HTMLButtonElement>) => {
      const current = state.current;
      if (!current || current.pointerId !== event.pointerId) return;
      current.x = event.clientX;
      current.y = event.clientY;
      if (!current.dragging && Math.hypot(event.clientX - current.startX, event.clientY - current.startY) < DRAG_THRESHOLD) return;
      if (!current.dragging) {
        current.dragging = true;
        current.handle.closest<HTMLElement>("[data-room-id], [data-group-id]")?.setAttribute("data-drag-source", "true");
        setActive(current.source);
        current.frame = requestAnimationFrame(scrollFrame);
      }
      updateTarget(current.source, event.clientX, event.clientY);
    },
    onPointerUp: (event: ReactPointerEvent<HTMLButtonElement>) => {
      if (state.current?.pointerId === event.pointerId) finish(true);
    },
    onPointerCancel: (event: ReactPointerEvent<HTMLButtonElement>) => {
      if (state.current?.pointerId === event.pointerId) finish(false);
    },
    onLostPointerCapture: (event: ReactPointerEvent<HTMLButtonElement>) => {
      if (state.current?.pointerId === event.pointerId) finish(false);
    },
    "aria-pressed": active?.kind === source.kind && active.id === source.id,
  }), [active, disabled, finish, scrollFrame, updateTarget]);

  useEffect(() => {
    const cancel = (event: KeyboardEvent) => {
      if (event.key === "Escape" && state.current) finish(false);
    };
    window.addEventListener("keydown", cancel);
    return () => window.removeEventListener("keydown", cancel);
  }, [finish]);

  useEffect(() => {
    if (state.current) finish(false);
  }, [disabled, finish, resetKey]);

  useEffect(() => () => {
    const current = state.current;
    if (current && current.frame !== null) cancelAnimationFrame(current.frame);
    current?.handle.closest<HTMLElement>("[data-room-id], [data-group-id]")?.removeAttribute("data-drag-source");
    if (current?.handle.hasPointerCapture?.(current.pointerId)) current.handle.releasePointerCapture(current.pointerId);
    current?.handle.removeAttribute("aria-pressed");
  }, []);

  return { active, handlers };
}
