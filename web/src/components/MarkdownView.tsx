import { createContext, useContext, useEffect, useMemo, type ComponentProps } from "react";
import { Typography } from "@mantine/core";
import ReactMarkdown, { type Components, type ExtraProps } from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkGemoji from "remark-gemoji";
import { Link as RouterLink } from "react-router-dom";
import { getUserId } from "../api/session";
import { useKnownNames } from "../utils/knownNames";
import { personPath } from "../utils/personLinks";
import { teamPath } from "../utils/teamLinks";
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
//   `groupMentions` does the same for `<@team:id>` (read as `@handle` when the list names the team), `<@watchers>` and `<@everyone>` (read as
//   themselves when the list holds the keyword); an unlisted group token stays the raw token.
//   A listed person's and team's mention is a router link to their page (1.2 D6: `/people/:id`, `/teams/:id`; the "mention" chip, underlined like
//   every link in a body and in the text colour), except inside a link's own text (`remarkMentions` flags it `data-in-link`: a link may not hold
//   a link), and `@everyone` and `@watchers` are never links. The variant therefore needs a router wherever it shows a mention.
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

/** One group mention the server listed (`MessageResponse.groupMentions`). */
export type GroupMentionItem = { kind: "TEAM" | "WATCHERS" | "EVERYONE"; teamId: number | null; teamHandle: string | null };

/**
 * The message variant's mention names (by id), the listed teams' handles (by team id), the listed keywords and the reader's own id, handed to
 * the `span` renderer through context for the same reason.
 */
type MentionContext = {
  names: ReadonlyMap<number, string>;
  teams: ReadonlyMap<number, string>;
  keywords: ReadonlySet<string>;
  me: number | null;
};
const MentionsContext = createContext<MentionContext>({ names: new Map(), teams: new Map(), keywords: new Set(), me: null });

function MessageSpan({ node: _node, ...props }: WithNode<"span">) {
  const { names, teams, keywords, me } = useContext(MentionsContext);
  // `data-mention*` is set only by `remarkMentions` (a user cannot write it: raw HTML is not rendered).
  const marks = props as { "data-mention"?: string; "data-mention-team"?: string; "data-mention-group"?: string; "data-in-link"?: string };
  // Inside a link's own text a mention stays a span: a link may not hold a link.
  const linked = marks["data-in-link"] === undefined;
  const team = marks["data-mention-team"];
  const group = marks["data-mention-group"];
  if (team !== undefined || group !== undefined) {
    const label = team !== undefined ? teams.get(Number(team)) : group !== undefined && keywords.has(group) ? group : undefined;
    // A group token the server did not list stays the raw token it was written as.
    if (label === undefined) return <>{props.children}</>;
    // `@everyone` and `@watchers` name no page: only a team is a link.
    return team !== undefined && linked ? (
      <RouterLink to={teamPath(Number(team))} className={classes.mention} data-mention-group="team" data-mention-team={team}>
        {`@${label}`}
      </RouterLink>
    ) : (
      <span className={classes.mention} data-mention-group={group ?? "team"} data-mention-team={team}>
        {`@${label}`}
      </span>
    );
  }
  const raw = marks["data-mention"];
  if (raw === undefined) return <span {...props} />;
  const id = Number(raw);
  const name = names.get(id);
  // An id the server did not list stays the raw token it was written as.
  if (name === undefined) return <>{props.children}</>;
  const self = id === me;
  const className = self ? `${classes.mention} ${classes.mentionSelf}` : classes.mention;
  return linked ? (
    <RouterLink to={personPath(id)} className={className} data-mention={raw} data-self={self ? "true" : undefined}>
      {`@${name}`}
    </RouterLink>
  ) : (
    <span className={className} data-mention={raw} data-self={self ? "true" : undefined}>
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
  groupMentions,
}: {
  children: string | null | undefined;
  variant?: MarkdownVariant;
  /** The message variant's hashtag links: where a tag leads (a router path; the tag is the typed spelling, not yet encoded). Needs a router. */
  tagHref?: (tag: string) => string;
  /** The message variant's mentioned people (`MessageResponse.mentions`): only an id listed here reads as a name. */
  mentions?: readonly { userId: number; name: string }[];
  /** The message variant's group mentions (`MessageResponse.groupMentions`): only a team, `@watchers` or `@everyone` listed here reads as one. */
  groupMentions?: readonly GroupMentionItem[];
}) {
  const message = variant === "message";
  const known = useKnownNames();
  const people = message && mentions !== undefined && mentions.length > 0 ? mentions : undefined;
  const groups = message && groupMentions !== undefined && groupMentions.length > 0 ? groupMentions : undefined;
  const named = people ?? groups;
  useEffect(() => {
    if (people !== undefined) known.remember(people);
    if (groups !== undefined) known.rememberTeams(groups);
  }, [people, groups, known]);
  const context = useMemo<MentionContext>(
    () => ({
      names: new Map((people ?? []).map((person) => [person.userId, person.name])),
      teams: new Map((groups ?? []).flatMap((group) => (group.kind === "TEAM" && group.teamId !== null && group.teamHandle !== null ? [[group.teamId, group.teamHandle] as const] : []))),
      keywords: new Set((groups ?? []).filter((group) => group.kind !== "TEAM").map((group) => group.kind.toLowerCase())),
      me: getUserId(),
    }),
    [people, groups],
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
