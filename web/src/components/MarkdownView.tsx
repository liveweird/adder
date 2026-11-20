import { createContext, useContext, useEffect, useMemo, type ComponentProps } from "react";
import { Typography } from "@mantine/core";
import ReactMarkdown, { type Components, type ExtraProps } from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkGemoji from "remark-gemoji";
import { Link as RouterLink } from "react-router-dom";
import { getUserId } from "../api/session";
import { useKnownNames } from "../utils/knownNames";
import remarkHashtags from "../utils/remarkHashtags";
import remarkMentions from "../utils/remarkMentions";
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
//   A message variant given a non-empty `mentions` also reads each `<@id>` token (`remarkMentions`): an id the list names reads `@Name` (marked
//   when it is the reader), any other id stays the raw token, because the server's list is the verdict. The name is a React text child, never Markdown or HTML.
export type MarkdownVariant = "document" | "message";

type WithNode<T extends keyof React.JSX.IntrinsicElements> = ComponentProps<T> & ExtraProps;

const boldParagraph = ({ node: _node, ...props }: WithNode<"h1">) => (
  <p {...(props as ComponentProps<"p">)} className={classes.heading} />
);

const REMARK_PLUGINS = [remarkGfm, remarkGemoji];
const REMARK_PLUGINS_WITH_TAGS = [remarkGfm, remarkGemoji, remarkHashtags];
const REMARK_PLUGINS_WITH_MENTIONS = [remarkGfm, remarkGemoji, remarkMentions];
const REMARK_PLUGINS_WITH_TAGS_AND_MENTIONS = [remarkGfm, remarkGemoji, remarkHashtags, remarkMentions];

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

/** The message variant's mention names (by id) and the reader's own id, handed to the `span` renderer through context for the same reason. */
type MentionContext = { names: ReadonlyMap<number, string>; me: number | null };
const MentionsContext = createContext<MentionContext>({ names: new Map(), me: null });

function MessageSpan({ node: _node, ...props }: WithNode<"span">) {
  const { names, me } = useContext(MentionsContext);
  // `data-mention` is set only by `remarkMentions` (a user cannot write it: raw HTML is not rendered).
  const raw = (props as { "data-mention"?: string })["data-mention"];
  if (raw === undefined) return <span {...props} />;
  const id = Number(raw);
  const name = names.get(id);
  // An id the server did not list stays the raw token it was written as.
  if (name === undefined) return <>{props.children}</>;
  const self = id === me;
  return (
    <span className={self ? `${classes.mention} ${classes.mentionSelf}` : classes.mention} data-mention={raw} data-self={self ? "true" : undefined}>
      {`@${name}`}
    </span>
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
  span: MessageSpan,
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
  mentions,
}: {
  children: string | null | undefined;
  variant?: MarkdownVariant;
  /** The message variant's hashtag links: where a tag leads (a router path; the tag is the typed spelling, not yet encoded). Needs a router. */
  tagHref?: (tag: string) => string;
  /** The message variant's mentioned people (`MessageResponse.mentions`): only an id listed here reads as a name. */
  mentions?: readonly { userId: number; name: string }[];
}) {
  const message = variant === "message";
  const known = useKnownNames();
  const named = message && mentions !== undefined && mentions.length > 0 ? mentions : undefined;
  useEffect(() => {
    if (named !== undefined) known.remember(named);
  }, [named, known]);
  const context = useMemo<MentionContext>(
    () => ({ names: new Map((named ?? []).map((person) => [person.userId, person.name])), me: getUserId() }),
    [named],
  );
  const withTags = message && tagHref !== undefined;
  const plugins =
    named === undefined
      ? withTags
        ? REMARK_PLUGINS_WITH_TAGS
        : REMARK_PLUGINS
      : withTags
        ? REMARK_PLUGINS_WITH_TAGS_AND_MENTIONS
        : REMARK_PLUGINS_WITH_MENTIONS;
  return (
    <Typography className={message ? classes.message : classes.document}>
      <TagHrefContext.Provider value={message ? tagHref : undefined}>
        <MentionsContext.Provider value={context}>
          <ReactMarkdown remarkPlugins={plugins} components={message ? messageComponents : undefined}>
            {children}
          </ReactMarkdown>
        </MentionsContext.Provider>
      </TagHrefContext.Provider>
    </Typography>
  );
}
