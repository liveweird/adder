import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useRef, type ComponentProps } from "react";
import { Typography } from "@mantine/core";
import ReactMarkdown, { type Components, type ExtraProps } from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkGemoji from "remark-gemoji";
import { Link as RouterLink } from "react-router-dom";
import type { MessageLinkSource } from "../api/linkPreviews";
import { getUserId } from "../api/session";
import { useThreadRefHref } from "../hooks/useThreadRefHref";
import { useKnownNames } from "../utils/knownNames";
import { personPath } from "../utils/personLinks";
import { focusThreadPaneState } from "../utils/roomLinks";
import { teamPath } from "../utils/teamLinks";
import remarkHashtags from "../utils/remarkHashtags";
import remarkMentions from "../utils/remarkMentions";
import remarkThreadRefs from "../utils/remarkThreadRefs";
import { useThreadReference } from "../utils/threadReferenceCache";
import ExternalMessageLink from "./ExternalMessageLink";
import classes from "./MarkdownView.module.css";

// Read-only renderer for the markdown strings the app carries. Single home for the Mantine
// Typography wrapper + the GFM/gemoji plugin set (Lettuce's, ported).
//
// Two variants:
// - "document" (default): long-form content under its own page chrome (the changelog bodies);
//   plain react-markdown output, unchanged.
// - "message": chat message bodies, where dozens of user-written bodies share one page, so
//   a body may not shape the page: headings become bold paragraphs (50 messages cannot flood
//   the heading outline). External links and images show their destination before opening and can
//   request a compact preview. Same-origin links stay direct.
//   Neither variant renders raw HTML (react-markdown's default; there is no rehype-raw).
//   A message variant given `tagHref` also turns each #hashtag (`remarkHashtags`) into an in-app router link (no `target`, the tag as
//   plain text inside it) to `tagHref(tag)`; the document variant never does, and without `tagHref` a tag stays text.
//   A message variant given `threadRoomId` validates each `#42` (`remarkThreadRefs`) through the bounded room batch read and links only readable
//   threads in that room. Missing, deleted, inaccessible and failed references stay text. Hashtags and thread numbers never overlap, and both skip code and links.
//   A message variant given a non-empty `mentions` also reads each `<@id>` token (`remarkMentions`): an id the list names reads `@Name` (marked
//   when it is the reader), any other id stays the raw token, because the server's list is the verdict. The name is a React text child, never Markdown or HTML.
//   `groupMentions` does the same for `<@team:id>` (read as `@handle` when the list names the team), `<@watchers>`, `<@here>` and `<@everyone>` (read as
//   themselves when the list holds the keyword); an unlisted group token stays the raw token.
//   A listed person's and team's mention is a router link to their page (1.2 D6: `/people/:id`, `/teams/:id`; the "mention" chip, underlined like
//   every link in a body and in the text colour), except inside a link's own text (`remarkMentions` flags it `data-in-link`: a link may not hold
//   a link), and group keywords are never links. The variant therefore needs a router wherever it shows a mention.
export type MarkdownVariant = "document" | "message";

type WithNode<T extends keyof React.JSX.IntrinsicElements> = ComponentProps<T> & ExtraProps;

const boldParagraph = ({ node: _node, ...props }: WithNode<"h1">) => (
  <p {...(props as ComponentProps<"p">)} className={classes.heading} />
);

const BASE_PLUGINS = [remarkGfm, remarkGemoji];

// The message variant's tag and validated-thread destinations, handed to its `a` renderer through context so the components map can stay ONE module constant: a map
// built per render is a new component type each time, which remounts every link and heading of every message (a keyboard user's focus
// on a #tag link would drop to <body> on any re-render, such as a live refetch).
type LinkHrefs = { tag?: (tag: string) => string; threadRoomId?: number };
const LinkHrefsContext = createContext<LinkHrefs>({});
const MessageSourceContext = createContext<MessageLinkSource | undefined>(undefined);
const LinkLabelContext = createContext<string | null>(null);

function MessageDestination({ href, title, children }: Pick<ComponentProps<"a">, "href" | "title" | "children">) {
  const source = useContext(MessageSourceContext);
  if (!href || !URL.canParse(href, window.location.href)) return <>{children}</>;
  const destination = new URL(href, window.location.href);
  if (destination.origin === window.location.origin) {
    return <RouterLink to={`${destination.pathname}${destination.search}${destination.hash}`} title={title}>{children}</RouterLink>;
  }
  return <ExternalMessageLink destination={destination} source={source} title={title}>{children}</ExternalMessageLink>;
}

function MessageLink({ node: _node, ...props }: WithNode<"a">) {
  const { tag: tagHref, threadRoomId } = useContext(LinkHrefsContext);
  // `data-hashtag` and `data-thread-number` are set only by `remarkHashtags` and `remarkThreadRefs` (a user cannot write them: raw HTML is not
  // rendered), so they mark the plugins' links.
  const marks = props as { "data-hashtag"?: string; "data-thread-number"?: string };
  const tag = marks["data-hashtag"];
  const threadNumber = marks["data-thread-number"];
  if (tagHref !== undefined && tag !== undefined) return <RouterLink to={tagHref(tag)}>{props.children}</RouterLink>;
  // The pane takes focus after the jump, as it does for a list row: the link is left behind in the conversation.
  return threadRoomId !== undefined && threadNumber !== undefined ? (
    <ValidatedThreadReference roomId={threadRoomId} number={Number(threadNumber)}>
      {props.children}
    </ValidatedThreadReference>
  ) : (
    <MessageDestination href={props.href} title={props.title}>
      <LinkLabelContext.Provider value={props.href ?? ""}>
        {typeof props.children === "string" && !props.children.trim() ? props.href : props.children ?? props.href}
      </LinkLabelContext.Provider>
    </MessageDestination>
  );
}

function ValidatedThreadReference({
  roomId,
  number,
  children,
}: {
  roomId: number;
  number: number;
  children: React.ReactNode;
}) {
  const readable = useThreadReference(roomId, number);
  const href = useThreadRefHref(roomId);
  const container = useRef<HTMLSpanElement>(null);
  const heldFocus = useRef(false);
  useLayoutEffect(() => {
    if (!readable && heldFocus.current) container.current?.focus({ preventScroll: true });
  }, [readable]);
  return (
    <span
      ref={container}
      tabIndex={-1}
      onFocusCapture={() => { heldFocus.current = true; }}
      onBlurCapture={() => { heldFocus.current = false; }}
    >
      {readable ? <RouterLink to={href(number)} state={focusThreadPaneState}>{children}</RouterLink> : children}
    </span>
  );
}

function MessageImage({ src, alt }: WithNode<"img">) {
  const inLink = useContext(LinkLabelContext);
  // A linked image contributes its label to the parent destination control. ReactMarkdown has already filtered unsafe sources.
  if (inLink !== null) return <span>{alt || inLink}</span>;
  if (typeof src !== "string" || src === "") return <span>{alt}</span>;
  return <MessageDestination href={src}>{alt || src}</MessageDestination>;
}

/** One group mention the server listed (`MessageResponse.groupMentions`). */
export type GroupMentionItem = { kind: "TEAM" | "WATCHERS" | "HERE" | "EVERYONE"; teamId: number | null; teamHandle: string | null };

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
    // Group keywords name no page: only a team is a link.
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
  img: MessageImage,
};

export default function MarkdownView({
  children,
  variant = "document",
  tagHref,
  threadRoomId,
  mentions,
  groupMentions,
  linkSource,
}: {
  children: string | null | undefined;
  variant?: MarkdownVariant;
  /** The message variant's hashtag links: where a tag leads (a router path; the tag is the typed spelling, not yet encoded). Needs a router. */
  tagHref?: (tag: string) => string;
  /** The room whose numeric references may become links after the authorization-aware batch read. */
  threadRoomId?: number;
  /** The message variant's mentioned people (`MessageResponse.mentions`): only an id listed here reads as a name. */
  mentions?: readonly { userId: number; name: string }[];
  /** The message variant's group mentions (`MessageResponse.groupMentions`): only a team or group keyword listed here reads as one. */
  groupMentions?: readonly GroupMentionItem[];
  linkSource?: MessageLinkSource;
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
  const withRefs = message && threadRoomId !== undefined;
  const withNames = named !== undefined;
  const plugins = useMemo(
    () => [...BASE_PLUGINS, ...(withTags ? [remarkHashtags] : []), ...(withNames ? [remarkMentions] : []), ...(withRefs ? [remarkThreadRefs] : [])],
    [withTags, withNames, withRefs],
  );
  const hrefs: LinkHrefs = {
    tag: message ? tagHref : undefined,
    threadRoomId: message ? threadRoomId : undefined,
  };
  return (
    <Typography className={message ? classes.message : classes.document}>
      <LinkHrefsContext.Provider value={hrefs}>
        <MessageSourceContext.Provider value={linkSource}>
          <MentionsContext.Provider value={context}>
            <ReactMarkdown remarkPlugins={plugins} components={message ? messageComponents : undefined}>
              {children}
            </ReactMarkdown>
          </MentionsContext.Provider>
        </MessageSourceContext.Provider>
      </LinkHrefsContext.Provider>
    </Typography>
  );
}
