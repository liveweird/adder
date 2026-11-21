import { Anchor, type AnchorProps } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import { roomPath } from "../utils/roomLinks";
import classes from "../theme.module.css";

/**
 * A room's name as a link to the room (`/rooms/:id`, where the Threads tab restores the person's remembered list). Underlined always, as
 * `PersonLink` is (a name that looks like plain text is no link); `quiet` mutes the underline's ink for dense lines; `inherit` takes the
 * surrounding font AND colour (a room named in a dimmed line stays dimmed). Never nest it inside another link — a row that is one link keeps the
 * room as text, or uses the card pattern of the notification drawer.
 */
export default function RoomLink({ id, name, quiet = false, underline = "always", className, ...anchorProps }: { id: number; name: string; quiet?: boolean } & Omit<AnchorProps, "children">) {
  return (
    <Anchor
      component={RouterLink}
      to={roomPath(id)}
      underline={underline}
      className={[quiet ? classes.anchorQuiet : undefined, className].filter(Boolean).join(" ") || undefined}
      {...(anchorProps.inherit && anchorProps.c === undefined ? { c: "inherit" } : {})}
      {...anchorProps}
    >
      {name}
    </Anchor>
  );
}
