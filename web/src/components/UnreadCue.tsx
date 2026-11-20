import type { ReactNode } from "react";
import { Badge, Box } from "@mantine/core";
import classes from "./UnreadCue.module.css";

const join = (...names: (string | false | undefined)[]) => names.filter(Boolean).join(" ");

/**
 * THE unread / unseen cue (checkup C-UI-1, "blue everywhere"): the rooms rail, the thread list, the "New" marker in a conversation, the bell,
 * the notice rows of its drawer and the what's-new marker all draw it, so a reader learns ONE sign. Blue, never red (red = blocking) and never
 * the olive brand (the interactive accent).
 *
 * With `children` it is a count badge (the text is the caller's: "3", "99+", "New"; `icon` leads it; `size` is `sm`, or `xs` in the tight rail
 * and menu rows), without them a dot. It is ALWAYS `aria-hidden`: the cue is a shape, and its words belong to the host's accessible name or
 * description ("3 unread messages", "New activity", "Unseen", a visually hidden "(What's new)"), so a screen reader hears them once and the
 * mark is never colour alone.
 */
export default function UnreadCue({ children, icon, size = "sm", className }: { children?: ReactNode; icon?: ReactNode; size?: "xs" | "sm"; className?: string }) {
  if (children === undefined) return <span aria-hidden="true" data-unread-cue="dot" className={join(classes.dot, className)} />;
  return (
    <Badge size={size} variant="filled" color="blue" leftSection={icon} aria-hidden="true" data-unread-cue="count" className={join(classes.count, className)}>
      {children}
    </Badge>
  );
}

/**
 * Wraps what the cue flags (the bell, the account avatar, a rail avatar) and pins the cue to its top right corner while `show` holds.
 * `count` is the badge text; without it the cue is a dot. `title` is the wrapper's. `ring` (default on) separates the cue from what is
 * underneath with a body-coloured ring; turn it off on a host whose surface is tinted.
 */
export function UnreadAnchor({ show, count, title, ring = true, children }: { show: boolean; count?: ReactNode; title?: string; ring?: boolean; children: ReactNode }) {
  return (
    <Box component="span" className={classes.anchor} title={title}>
      {children}
      {show && <UnreadCue className={join(classes.corner, ring && classes.ring)}>{count}</UnreadCue>}
    </Box>
  );
}
