import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, Badge, Group, Paper, Stack, Text, Title } from "@mantine/core";
import type { RoomWorkflow } from "../api/rooms";
import type { MessageSearchHit, ThreadSearchHit } from "../api/search";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { threadMessagePath, threadPath } from "../utils/roomLinks";
import PersonLink from "./PersonLink";
import RoomLink from "./RoomLink";
import Snippet from "./Snippet";
import StatusPill from "./StatusPill";
import TagPills from "./TagPills";
import ThreadStatusPill from "./ThreadStatusPill";
import classes from "./SearchResults.module.css";

/** The plain words of a snippet: the accessible name of a link whose visible text `<mark>` splits. */
const plain = (segments: readonly { text: string }[]) => segments.map((segment) => segment.text).join("");

function MessageRow({ hit, message, zone }: { hit: ThreadSearchHit; message: MessageSearchHit; zone: string }) {
  return (
    <Anchor component={RouterLink} to={threadMessagePath(hit.roomId, hit.threadNumber, message.id)} className={classes.message} underline="hover">
      <Text size="xs" c="dimmed">
        {message.authorName} · {formatDateTime(message.createdAt, zone)}
      </Text>
      <Text size="sm" style={{ overflowWrap: "anywhere" }}>
        <Snippet segments={message.snippet} />
      </Text>
    </Anchor>
  );
}

/**
 * One search hit: the thread (a heading whose link goes to the thread) with where it stands, then up to three of its newest matching
 * messages, each a link that names the message to jump to. Where the room's workflow is at hand (the room's own search) the status is its pill;
 * a search across rooms carries no workflow, so it shows Open or Closed, the room and an Archived badge for an archived room. The room and the owner are
 * links (1.2 D6: `RoomLink`, `PersonLink`), siblings of the thread's heading link; the tags are plain pills; a matching message's row is ONE link to the message, so its author
 * (in the dimmed line of that link) stays text rather than nest a link in a link.
 */
function Hit({ hit, statuses, showRoom, zone }: { hit: ThreadSearchHit; statuses: RoomWorkflow["statuses"] | undefined; showRoom: boolean; zone: string }) {
  const { t } = useTranslation();
  return (
    <Paper component="article" withBorder className={classes.card}>
      <Stack gap="sm">
        <div className={classes.headingRow}>
          <Title order={showRoom ? 3 : 4} size="h5" className={classes.title}>
            {/* `<mark>` splits the visible words into pieces, so the link is named by the plain title. */}
            <Anchor component={RouterLink} to={threadPath(hit.roomId, hit.threadNumber)} inherit aria-label={`#${hit.threadNumber} ${plain(hit.titleSnippet)}`}>
              <Text span c="dimmed" inherit className={classes.number}>{`#${hit.threadNumber} `}</Text>
              <Snippet segments={hit.titleSnippet} />
            </Anchor>
          </Title>
          <Group gap="xs" className={classes.state}>
            {showRoom ? (
              // Neutral gray for both: with no workflow at hand there is no status colour to show, and blue means "in progress"
              // (web/CLAUDE.md); gray is what a room's initial "Open" status wears. The word, not the hue, tells the two apart.
              <StatusPill color="gray" dot size="sm">
                {t(hit.closed ? "search.results.closed" : "search.results.open")}
              </StatusPill>
            ) : (
              <ThreadStatusPill statuses={statuses} statusKey={hit.status} size="sm" wrap />
            )}
            {showRoom && hit.roomArchived && (
              <Badge color="gray" size="sm">
                {t("rooms.archivedBadge")}
              </Badge>
            )}
          </Group>
        </div>
        <Group gap="xs" className={classes.meta}>
          {showRoom && <RoomLink id={hit.roomId} name={hit.roomName} size="xs" />}
          <Text size="xs" c="dimmed">
            <PersonLink id={hit.ownerId} name={hit.ownerName} inherit /> · {t("threads.row.messages", { count: hit.messageCount })} · {formatDateTime(hit.lastActivityAt, zone)}
          </Text>
        </Group>
        {/* The thread's curated tags (1.2 D3), whatever the search matched on: plain pills (a retired one struck through and said in words). */}
        {hit.tags.length > 0 && (
          <div role="group" aria-label={t("threads.tags.aria")}>
            <TagPills tags={hit.tags} />
          </div>
        )}
        {hit.matchCount > 0 && (
          <div className={classes.matches}>
            <Text size="sm" fw={500}>
              {t("search.results.matches", { count: hit.matchCount })}
            </Text>
            <Stack component="ul" gap={4} m={0} p={0} className={classes.messageList}>
              {hit.messages.map((message) => (
                <li key={message.id}>
                  <MessageRow hit={hit} message={message} zone={zone} />
                </li>
              ))}
            </Stack>
          </div>
        )}
      </Stack>
    </Paper>
  );
}

/** The hits of one page as a list of articles, each a thread. `statuses` is the room's workflow (the room's own search); `showRoom` is the search across rooms. */
export default function SearchResults({ hits, statuses, showRoom, label }: { hits: readonly ThreadSearchHit[]; statuses?: RoomWorkflow["statuses"]; showRoom: boolean; label: string }) {
  const zone = browserTimeZone();
  return (
    <Stack component="ol" aria-label={label} gap="sm" m={0} p={0} className={classes.results}>
      {hits.map((hit) => (
        <li key={`${hit.roomId}/${hit.threadNumber}`}>
          <Hit hit={hit} statuses={statuses} showRoom={showRoom} zone={zone} />
        </li>
      ))}
    </Stack>
  );
}
