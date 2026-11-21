import { Anchor, type AnchorProps } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import { personPath } from "../utils/personLinks";
import classes from "../theme.module.css";

/**
 * A person's name as a link to their profile (`/people/:id`). The link is not checked when drawn: a person who is gone but still named (a soft-deleted
 * author, a listed manager) opens the profile's "not available" page (1.2 A15); a site that KNOWS the person is gone (a history actor flagged
 * `actorDeleted`, a field value the thread no longer names) draws plain text instead of a `PersonLink`. Never nest it inside another link — a row
 * that is one link keeps the name as text, or uses the card pattern of the bell's rows.
 *
 * Underlined ALWAYS (`underline="always"`, overridable): the theme draws every Anchor as dark text with an underline only on hover ("links are
 * text, not accent"), which leaves a bare name or a list entry looking like plain text; the underline marks a link by shape, not colour, and
 * leaves the theme's colours (and their AA pins) alone. The profile's own lists share the choice. `quiet` keeps the underline but mutes its ink
 * (hover restores the accent): for dense columns of names, such as a message's author line. `inherit` (the font) also takes the surrounding
 * COLOUR, so a name inside a dimmed line stays dimmed and the underline is the cue; pass `c` to say otherwise.
 */
export default function PersonLink({
  id,
  name,
  quiet = false,
  underline = "always",
  className,
  ...anchorProps
}: { id: number; name: string; quiet?: boolean } & Omit<AnchorProps, "children">) {
  return (
    <Anchor
      component={RouterLink}
      to={personPath(id)}
      underline={underline}
      className={[quiet ? classes.anchorQuiet : undefined, className].filter(Boolean).join(" ") || undefined}
      {...(anchorProps.inherit && anchorProps.c === undefined ? { c: "inherit" } : {})}
      {...anchorProps}
    >
      {name}
    </Anchor>
  );
}
