import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useDebouncedValue } from "@mantine/hooks";
import { listRoomMembers } from "../api/rooms";
import { listTeams } from "../api/teams";
import type { KnownNames } from "../utils/knownNames";
import type { MentionQuery } from "../utils/mentionText";
import { roomMemberPickerKey } from "../utils/roomMemberOptions";
import { foldDiacritics } from "../utils/text";
import { FILTER_DEBOUNCE_MS } from "./usePagedSort";

/** Rows each group offers (the server filters; the rest are one more letter away). */
const PEOPLE_LIST_SIZE = 8;
const TEAMS_LIST_SIZE = 5;

/** The composer's three result groups, in the order the list shows them. */
export type CandidateGroup = "people" | "teams" | "groups";

/** One row of the mention list: the token a pick inserts, what the row says, and what the session learns from the pick. */
export type Candidate = {
  key: string;
  group: CandidateGroup;
  /** The canonical token a pick inserts (`<@42>`, `<@team:5>`, `<@everyone>`, `<@here>`, `<@watchers>`). */
  token: string;
  /** `Name` of a person, `@handle` of a team, or a group keyword such as `@here`. */
  name: string;
  /** A person's email, a team's name; a keyword says whom it reaches in the list itself (`detailKey`). */
  detail?: string;
  /** The i18n key of a keyword row's description. */
  detailKey?: "threads.mention.everyone" | "threads.mention.watchers" | "threads.mention.here";
  remember: (known: KnownNames) => void;
};

/** What a team handle can be spelled with (V25: lowercase ASCII words joined by hyphens, at most 30): any other term cannot match one. */
const TEAM_TERM = /^[a-z0-9-]{1,30}$/;

/** The words that reach each keyword: typed from the start (`@wat` finds the watchers, so does `@obs`). */
const KEYWORDS = [
  { word: "everyone", words: ["everyone"], detailKey: "threads.mention.everyone" as const },
  { word: "here", words: ["here", "active", "online"], detailKey: "threads.mention.here" as const },
  { word: "watchers", words: ["watchers", "observers"], detailKey: "threads.mention.watchers" as const },
];

/**
 * What the `@term` being typed can mention, read through the server and filtered by the term: the room's members (`GET /rooms/{id}/members?name=`),
 * the teams with a handle that match the term (`GET /teams?handle=`, only once a letter is typed) and the keywords (`@everyone`, `@here`, `@watchers`;
 * the latter only when [offerWatchers], since a thread that does not exist yet has none). `settled` is true when the reads match the term as it
 * stands, so a quick Enter never picks a row the typed letters no longer match: until then each list is the previous read narrowed by the term.
 */
export function useMentionCandidates(roomId: number, query: MentionQuery | null, offerWatchers: boolean): { candidates: Candidate[]; settled: boolean } {
  const [term] = useDebouncedValue(query?.term ?? "", FILTER_DEBOUNCE_MS);
  const members = useQuery({
    // Under the room's members key (and so under ["rooms"]): a room refresh re-reads it.
    queryKey: [...roomMemberPickerKey(roomId), "mention", term],
    queryFn: () => listRoomMembers(roomId, { page: 1, pageSize: PEOPLE_LIST_SIZE, sort: "name", name: term || undefined }),
    enabled: query !== null,
    placeholderData: keepPreviousData,
  });
  const typedTerm = query?.term ?? "";
  const teamsWanted = query !== null && TEAM_TERM.test(foldDiacritics(typedTerm).trim().toLowerCase());
  const handleTerm = foldDiacritics(term).trim().toLowerCase();
  const teams = useQuery({
    // Teams are not a room's data: their own prefix, read from the first letter on, and only for a term a handle could hold.
    queryKey: ["teams", "mention", handleTerm],
    queryFn: () => listTeams({ page: 1, pageSize: TEAMS_LIST_SIZE, sort: "handle", handle: handleTerm }),
    enabled: teamsWanted && TEAM_TERM.test(handleTerm),
    placeholderData: keepPreviousData,
  });
  const typed = foldDiacritics(typedTerm).trim();
  const inSync = query !== null && term === query.term;
  const peopleSettled = members.data !== undefined && !members.isFetching && inSync;
  const teamsSettled = !teamsWanted || (!teams.isFetching && inSync);
  const settled = peopleSettled && teamsSettled;

  const people: Candidate[] = (members.data?.items ?? [])
    .filter((member) => peopleSettled || foldDiacritics(member.name).includes(typed))
    .map((member) => ({
      key: `user-${member.userId}`,
      group: "people",
      token: `<@${member.userId}>`,
      name: member.name,
      detail: member.email,
      remember: (known) => known.remember([{ userId: member.userId, name: member.name }]),
    }));
  const teamRows: Candidate[] = !teamsWanted
    ? []
    : (teams.data?.items ?? [])
        .filter((team): team is typeof team & { handle: string } => team.handle !== null && team.handle !== undefined)
        .filter((team) => foldDiacritics(team.handle).includes(typed.toLowerCase()))
        .map((team) => ({
          key: `team-${team.id}`,
          group: "teams",
          token: `<@team:${team.id}>`,
          name: `@${team.handle}`,
          detail: team.name,
          remember: (known) => known.rememberTeams([{ teamId: team.id, teamHandle: team.handle }]),
        }));
  const folded = typed.toLowerCase();
  const keywordRows: Candidate[] = KEYWORDS.filter((keyword) => (keyword.word !== "watchers" || offerWatchers) && keyword.words.some((word) => word.startsWith(folded))).map((keyword) => ({
    key: `group-${keyword.word}`,
    group: "groups",
    token: `<@${keyword.word}>`,
    name: `@${keyword.word}`,
    detailKey: keyword.detailKey,
    remember: () => undefined,
  }));
  return { candidates: [...people, ...teamRows, ...keywordRows], settled };
}
