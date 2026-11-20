import { Link as RouterLink } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, Badge, Group, Paper, Stack, Text, Title } from "@mantine/core";
import type { RoomWorkflow } from "../api/rooms";
import type { MessageSearchHit, ThreadSearchHit } from "../api/search";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { threadMessagePath, threadPath } from "../utils/roomLinks";
import Snippet from "./Snippet";
import StatusPill from "./StatusPill";
import ThreadStatusPill from "./ThreadStatusPill";

/** The plain words of a snippet: the accessible name of a link whose visible text `<mark>` splits. */
const plain = (segments: readonly { text: string }[]) => segments.map((segment) => segment.text).join("");

function MessageRow({ hit, message, zone }: { hit: ThreadSearchHit; message: MessageSearchHit; zone: string }) {
  return (
    <Anchor component={RouterLink} to={threadMessagePath(hit.roomId, hit.threadNumber, message.id)} display="block" underline="hover">
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
 * a search across rooms carries no workflow, so it shows Open or Closed, the room and an Archived badge for an archived room.
 */
function Hit({ hit, statuses, showRoom, zone }: { hit: ThreadSearchHit; statuses: RoomWorkflow["statuses"] | undefined; showRoom: boolean; zone: string }) {
  const { t } = useTranslation();
  return (
    <Paper component="article" withBorder p="sm">
      <Stack gap="xs">
        <Title order={3} size="h5" style={{ overflowWrap: "anywhere" }}>
          {/* `<mark>` splits the visible words into pieces, so the link is named by the plain title. */}
          <Anchor component={RouterLink} to={threadPath(hit.roomId, hit.threadNumber)} inherit aria-label={`#${hit.threadNumber} ${plain(hit.titleSnippet)}`}>
            <Text span c="dimmed" inherit>{`#${hit.threadNumber} `}</Text>
            <Snippet segments={hit.titleSnippet} />
          </Anchor>
        </Title>
        <Group gap="xs">
          {showRoom ? (
            // Neutral gray for both: with no workflow at hand there is no status colour to show, and blue means "in progress"
            // (web/CLAUDE.md); gray is what a room's initial "Open" status wears. The word, not the hue, tells the two apart.
            <StatusPill color="gray" dot size="sm">
              {t(hit.closed ? "search.results.closed" : "search.results.open")}
            </StatusPill>
          ) : (
            <ThreadStatusPill statuses={statuses} statusKey={hit.status} size="sm" />
          )}
          {showRoom && (
            <Text size="xs" c="dimmed">
              {hit.roomName}
            </Text>
          )}
          {showRoom && hit.roomArchived && (
            <Badge color="gray" size="sm">
              {t("rooms.archivedBadge")}
            </Badge>
          )}
          <Text size="xs" c="dimmed">
            {hit.ownerName} · {t("threads.row.messages", { count: hit.messageCount })} · {formatDateTime(hit.lastActivityAt, zone)}
          </Text>
        </Group>
        {hit.matchCount > 0 && (
          <>
            <Text size="sm" fw={500}>
              {t("search.results.matches", { count: hit.matchCount })}
            </Text>
            <Stack component="ul" gap="xs" m={0} p={0} style={{ listStyle: "none" }}>
              {hit.messages.map((message) => (
                <li key={message.id}>
                  <MessageRow hit={hit} message={message} zone={zone} />
                </li>
              ))}
            </Stack>
          </>
        )}
      </Stack>
    </Paper>
  );
}

/** The hits of one page as a list of articles, each a thread. `statuses` is the room's workflow (the room's own search); `showRoom` is the search across rooms. */
export default function SearchResults({ hits, statuses, showRoom, label }: { hits: readonly ThreadSearchHit[]; statuses?: RoomWorkflow["statuses"]; showRoom: boolean; label: string }) {
  const zone = browserTimeZone();
  return (
    <Stack component="ol" aria-label={label} gap="sm" m={0} p={0} style={{ listStyle: "none" }}>
      {hits.map((hit) => (
        <li key={`${hit.roomId}/${hit.threadNumber}`}>
          <Hit hit={hit} statuses={statuses} showRoom={showRoom} zone={zone} />
        </li>
      ))}
    </Stack>
  );
}
