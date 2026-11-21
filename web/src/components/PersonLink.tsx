import { Anchor, type AnchorProps } from "@mantine/core";
import { Link as RouterLink } from "react-router-dom";
import { personPath } from "../utils/personLinks";

/**
 * A person's name as a link to their profile (`/people/:id`). The link is not checked when drawn: a removed person's link opens the profile's
 * "not available" page (1.2 A15). Never nest it inside another link — a row that is one link keeps the name as text.
 *
 * Underlined ALWAYS (`underline="always"`, overridable): the theme draws every Anchor as dark text with an underline only on hover ("links are
 * text, not accent"), which leaves a bare name or a list entry looking like plain text; the underline marks a link by shape, not colour, and
 * leaves the theme's colours (and their AA pins) alone. The profile's own lists share the choice.
 */
export default function PersonLink({ id, name, ...anchorProps }: { id: number; name: string } & Omit<AnchorProps, "children">) {
  return (
    <Anchor component={RouterLink} to={personPath(id)} underline="always" {...anchorProps}>
      {name}
    </Anchor>
  );
}
