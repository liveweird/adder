import { useId, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useParams } from "react-router-dom";
import { Anchor, Avatar, Badge, Button, Group, Paper, Stack, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconPencil, IconUsers, IconDoor, type Icon } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { getUserId } from "../api/session";
import { listRooms } from "../api/rooms";
import { listTeams } from "../api/teams";
import { getUserDirectoryEntry } from "../api/users";
import { useAdmin } from "../auth";
import EditPageLoadState from "../components/EditPageLoadState";
import FormErrorAlert from "../components/FormErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import UnavailablePage from "../components/UnavailablePage";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { mailtoHref } from "../utils/mailto";
import { parsePersonId } from "../utils/personLinks";
import { roomKeys } from "../utils/roomKeys";
import { roomPath } from "../utils/roomLinks";
import { loadErrorMessage } from "../utils/saveError";
import { teamPath } from "../utils/teamLinks";
import { initialsOf } from "../utils/text";
import { editUserPath } from "../utils/userLinks";
import classes from "./Person.module.css";

/** One page holds the whole list the profile shows (the server's ceiling); beyond it the section says how many are not shown. */
const SECTION_PAGE_SIZE = 100;

/** What the profile shows of someone who is not there: a removed account and an unknown id are ONE page (no existence disclosure). */
function NotAvailable() {
  const { t } = useTranslation();
  return <UnavailablePage title={t("people.notAvailable.title")} description={t("people.notAvailable.message")} />;
}

/** A profile section: an h3, then the loading block, the load failure, the empty sentence or the list (with a note when the page cut it). */
function Section<T extends { id: number }>({
  title,
  query,
  emptyLabel,
  failedLabel,
  render,
  icon: Icon,
}: {
  title: string;
  query: { isLoading: boolean; data?: { items: readonly T[]; total: number } };
  emptyLabel: string;
  failedLabel: string;
  render: (item: T) => ReactNode;
  icon: Icon;
}) {
  const { t } = useTranslation();
  const headingId = useId();
  const data = query.data;
  let body: ReactNode;
  if (query.isLoading) body = <LoadingBlock mih={48} />;
  else if (!data) body = <FormErrorAlert>{failedLabel}</FormErrorAlert>;
  else if (data.items.length === 0) body = <Text size="sm" c="dimmed" className={classes.sectionNote}>{emptyLabel}</Text>;
  else {
    body = (
      <>
        <ul role="list" className={classes.membership} aria-labelledby={headingId}>
          {data.items.map((item) => (
            <li key={item.id}>{render(item)}</li>
          ))}
        </ul>
        {data.total > data.items.length && (
          <Text size="sm" c="dimmed" className={classes.sectionNote}>
            {t("people.capped", { shown: data.items.length, total: data.total })}
          </Text>
        )}
      </>
    );
  }
  return (
    <Paper component="section" aria-labelledby={headingId} withBorder className={classes.section}>
      <Group gap="xs" wrap="nowrap" className={classes.sectionHeading}>
        <Icon size={19} aria-hidden="true" />
        <Title id={headingId} order={3} size="h4">{title}</Title>
      </Group>
      {body}
    </Paper>
  );
}

/**
 * A person's profile (`/people/:id`, 1.2 D7), open to every signed-in user: the directory's own name and email, the person's teams, and the rooms the
 * viewer SHARES with them (an ADMIN: every room the person is in, and an "Edit user" link). Never the role, flags, language or MFA. A removed account,
 * an unknown id and an unreadable id are one "not available" page. UX only: the server decides what each list holds.
 */
export default function Person() {
  const { t } = useTranslation();
  const { id: idParam } = useParams();
  const id = parsePersonId(idParam);
  const admin = useAdmin();
  const person = useQuery({
    queryKey: ["people", id],
    queryFn: () => getUserDirectoryEntry(id as number),
    enabled: id !== null,
  });
  const ready = person.isSuccess;
  const teams = useQuery({
    queryKey: ["teams", "member", id],
    queryFn: () => listTeams({ page: 1, pageSize: SECTION_PAGE_SIZE, sort: "name", memberId: id as number }),
    enabled: ready,
  });
  const rooms = useQuery({
    queryKey: roomKeys.memberRooms(id as number),
    queryFn: () => listRooms({ page: 1, pageSize: SECTION_PAGE_SIZE, sort: "name", memberId: id as number }),
    enabled: ready,
  });

  if (id === null || (person.error instanceof ApiError && person.error.status === 404)) return <NotAvailable />;
  // The error state is for a read that never produced a profile: a failed BACKGROUND refetch keeps the loaded one on screen.
  if (!person.data) {
    return (
      <EditPageLoadState
        isLoading={person.isLoading}
        message={loadErrorMessage(person.error, t)}
        backTo="/"
        backLabel={t("common.notFound.backHome")}
        title={t("people.title")}
      />
    );
  }
  const data = person.data;
  const own = getUserId() === data.id;
  const roomsTitle = admin ? t("people.rooms.titleAdmin") : own ? t("people.rooms.titleOwn") : t("people.rooms.titleShared");
  const roomsEmpty = admin || own ? t("people.rooms.emptyAll") : t("people.rooms.emptyShared");

  return (
    <Stack gap="lg" maw={CONTENT_MAX_WIDTH}>
      <div className={classes.identity}>
        <Avatar variant="light" color="adder" size="lg" radius="md" aria-hidden="true">{initialsOf(data.name)}</Avatar>
        <PageHeader
          title={data.name}
          description={
            <Anchor href={mailtoHref(data.email)} underline="always" aria-label={t("people.emailAria", { email: data.email })}>
              {data.email}
            </Anchor>
          }
          actions={
            admin && (
              <Button component={RouterLink} to={editUserPath(data.id)} variant="default" leftSection={<IconPencil size={16} />}>
                {t("users.editUser")}
              </Button>
            )
          }
        />
      </div>
      <div className={classes.sections}>
        <Section
          title={t("people.teams.title")}
          icon={IconUsers}
          query={teams}
          emptyLabel={t("people.teams.empty")}
          failedLabel={t("people.teams.loadFailed")}
          render={(team: { id: number; name: string }) => (
            <Anchor className={classes.membershipLink} component={RouterLink} to={teamPath(team.id)} underline="always">
              {team.name}
            </Anchor>
          )}
        />
        <Section
          title={roomsTitle}
          icon={IconDoor}
          query={rooms}
          emptyLabel={roomsEmpty}
          failedLabel={t("people.rooms.loadFailed")}
          render={(room: { id: number; name: string; archived: boolean }) => (
            <Group gap="xs" wrap="nowrap">
              <Anchor className={classes.membershipLink} component={RouterLink} to={roomPath(room.id)} underline="always">
                {room.name}
              </Anchor>
              {room.archived && (
                <Badge variant="light" color="gray" className={classes.archived}>
                  {t("rooms.archivedBadge")}
                </Badge>
              )}
            </Group>
          )}
        />
      </div>
    </Stack>
  );
}
