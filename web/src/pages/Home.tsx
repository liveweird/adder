import { Avatar, Box, Button, Group, Stack, Text, Title, VisuallyHidden } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconArrowRight, IconDoor } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import UnreadCue from "../components/UnreadCue";
import { railQuery } from "../hooks/useRailRooms";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { roomPath, roomsPath } from "../utils/roomLinks";
import { initialsOf } from "../utils/text";
import classes from "./Home.module.css";

const HOME_ROOM_LIMIT = 6;

/** The signed-in landing page: a short, activity-ordered way back into the caller's rooms. */
export default function Home() {
  const { t } = useTranslation();
  const { data, isLoading, isError, error } = useQuery(railQuery(false));
  const rooms = data?.items.slice(0, HOME_ROOM_LIMIT) ?? [];
  const zone = browserTimeZone();

  return (
    <Stack gap="xl" className={classes.root}>
      <PageHeader
        title={t("home.title")}
        actions={
          <Button component={RouterLink} to={roomsPath} rightSection={<IconArrowRight size={16} />}>
            {t("home.allRooms")}
          </Button>
        }
      />

      <Box component="section" aria-labelledby="home-active-rooms" className={classes.panel}>
        <div className={classes.panelHeader}>
          <Title order={3} id="home-active-rooms">
            {t("home.recent.title")}
          </Title>
          <Text c="dimmed" size="sm" mt={2}>
            {t("home.recent.description")}
          </Text>
        </div>

        {isLoading && <LoadingBlock mih={180} />}
        {isError && (
          <div className={classes.state}>
            <ErrorAlert error={error} title={t("home.recent.loadFailed")} />
          </div>
        )}
        {data && rooms.length === 0 && (
          <EmptyState icon={IconDoor} label={t("home.empty.label")} hint={t("home.empty.hint")} />
        )}
        {rooms.length > 0 && (
          <div className={classes.roomList}>
            {rooms.map((room) => (
              <RouterLink key={room.id} to={roomPath(room.id)} className={classes.roomLink}>
                <Group gap="sm" wrap="nowrap" className={classes.roomIdentity}>
                  <Avatar variant="light" color="gray" size={36} radius="md" className={classes.roomIcon} aria-hidden="true">
                    {initialsOf(room.name)}
                  </Avatar>
                  <div className={classes.roomCopy}>
                    <Text component="span" fw={600} size="sm" className={classes.roomName}>
                      {room.name}
                    </Text>
                    {room.description && (
                      <Text component="span" c="dimmed" size="xs" lineClamp={1} className={classes.roomDescription}>
                        {room.description}
                      </Text>
                    )}
                  </div>
                </Group>

                <div className={classes.activity}>
                  {room.unread && (
                    <>
                      <UnreadCue size="xs">{t("home.recent.unread")}</UnreadCue>
                      <VisuallyHidden>{t("home.recent.unreadAccessible")}</VisuallyHidden>
                    </>
                  )}
                  <Text component="span" c="dimmed" size="xs" className={classes.activityTime}>
                    {t("home.recent.lastActivity", { when: formatDateTime(room.lastActivityAt, zone) })}
                  </Text>
                  <IconArrowRight size={16} aria-hidden="true" className={classes.rowArrow} />
                </div>
              </RouterLink>
            ))}
          </div>
        )}
      </Box>
    </Stack>
  );
}
