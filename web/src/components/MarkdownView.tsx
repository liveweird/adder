import { createContext, useContext, type ComponentProps } from "react";
import { Typography } from "@mantine/core";
import ReactMarkdown, { type Components, type ExtraProps } from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkGemoji from "remark-gemoji";
import { Link as RouterLink } from "react-router-dom";
import remarkHashtags from "../utils/remarkHashtags";
import classes from "./MarkdownView.module.css";

// Read-only renderer for the markdown strings the app carries. Single home for the Mantine
// Typography wrapper + the GFM/gemoji plugin set (Lettuce's, ported).
//
// Two variants:
// - "document" (default): long-form content under its own page chrome (the changelog bodies);
//   plain react-markdown output, unchanged.
// - "message": chat message bodies, where dozens of user-written bodies share one page, so
//   a body may not shape the page: headings become bold paragraphs (50 messages cannot flood
//   the heading outline), an image becomes a link (the CSP is `img-src 'self' data:`, a remote
//   image would be blocked anyway), a link opens in a new tab with `rel="noopener noreferrer"`.
//   Neither variant renders raw HTML (react-markdown's default; there is no rehype-raw).
//   A message variant given `tagHref` also turns each #hashtag (`remarkHashtags`) into an in-app router link (no `target`, the tag as
//   plain text inside it) to `tagHref(tag)`; the document variant never does, and without `tagHref` a tag stays text.
export type MarkdownVariant = "document" | "message";

type WithNode<T extends keyof React.JSX.IntrinsicElements> = ComponentProps<T> & ExtraProps;

const boldParagraph = ({ node: _node, ...props }: WithNode<"h1">) => (
  <p {...(props as ComponentProps<"p">)} className={classes.heading} />
);

const REMARK_PLUGINS = [remarkGfm, remarkGemoji];
const REMARK_PLUGINS_WITH_TAGS = [remarkGfm, remarkGemoji, remarkHashtags];

// The message variant's `tagHref`, handed to its `a` renderer through context so the components map can stay ONE module constant: a map
// built per render is a new component type each time, which remounts every link and heading of every message (a keyboard user's focus
// on a #tag link would drop to <body> on any re-render, such as a live refetch).
const TagHrefContext = createContext<((tag: string) => string) | undefined>(undefined);

function MessageLink({ node: _node, ...props }: WithNode<"a">) {
  const tagHref = useContext(TagHrefContext);
  // `data-hashtag` is set only by `remarkHashtags` (a user cannot write it: raw HTML is not rendered), so it marks the plugin's links.
  const tag = (props as { "data-hashtag"?: string })["data-hashtag"];
  return tagHref !== undefined && tag !== undefined ? (
    <RouterLink to={tagHref(tag)}>{props.children}</RouterLink>
  ) : (
    <a {...props} target="_blank" rel="noopener noreferrer" />
  );
}

const messageComponents: Components = {
  h1: boldParagraph,
  h2: boldParagraph,
  h3: boldParagraph,
  h4: boldParagraph,
  h5: boldParagraph,
  h6: boldParagraph,
  a: MessageLink,
  // react-markdown's default urlTransform already blanks an unsafe `src` (javascript:, data: …);
  // with nothing to link to, the alt text stays as plain text.
  img: ({ node: _node, src, alt }: WithNode<"img">) =>
    typeof src === "string" && src !== "" ? (
      <a href={src} target="_blank" rel="noopener noreferrer">
        {alt || src}
      </a>
    ) : (
      <span>{alt}</span>
    ),
};

export default function MarkdownView({
  children,
  variant = "document",
  tagHref,
}: {
  children: string | null | undefined;
  variant?: MarkdownVariant;
  /** The message variant's hashtag links: where a tag leads (a router path; the tag is the typed spelling, not yet encoded). Needs a router. */
  tagHref?: (tag: string) => string;
}) {
  const message = variant === "message";
  return (
    <Typography className={message ? classes.message : undefined}>
      <TagHrefContext.Provider value={message ? tagHref : undefined}>
        <ReactMarkdown
          remarkPlugins={message && tagHref !== undefined ? REMARK_PLUGINS_WITH_TAGS : REMARK_PLUGINS}
          components={message ? messageComponents : undefined}
        >
          {children}
        </ReactMarkdown>
      </TagHrefContext.Provider>
    </Typography>
  );
}
