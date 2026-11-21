import { Fragment, type ReactNode } from "react";

/**
 * A translated sentence that holds React nodes (a person's link inside "Owner: …"), without `<Trans>`: Trans parses the INTERPOLATED string as
 * markup, and the values here are what people typed (a thread's title, a display name), so `<strong>` or a tag named like one of the components
 * would become elements. Here the sentence is translated with a marker in place of each node (`slot("person")` as the value, so the keys stay
 * plain `{{person}}` placeholders and parity is key-for-key as ever) and the result is cut at the markers; everything else stays a text child.
 *
 * The marker is U+0001, which the server refuses in every name and title (`sanitizeSingleLine`); a marker that names no node renders nothing.
 */
const MARK = "\u0001";
const SLOT = new RegExp(`${MARK}(\\w+)${MARK}`, "u");

export const slot = (name: string): string => `${MARK}${name}${MARK}`;

export function fillSlots(text: string, nodes: Readonly<Record<string, ReactNode>>): ReactNode {
  // `split` with one capture group alternates text, name, text, name, …
  const parts = text.split(new RegExp(SLOT, "gu"));
  return parts.map((part, index) => <Fragment key={index}>{index % 2 === 0 ? part : (Object.hasOwn(nodes, part) ? nodes[part] : null)}</Fragment>);
}
