import { Link as RouterLink, useLocation, useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Anchor, Group } from "@mantine/core";
import { IconArrowLeft, IconMessages } from "@tabler/icons-react";
import type { RoomResponse } from "../api/rooms";
import EmptyState from "../components/EmptyState";
import NewThreadForm from "../components/NewThreadForm";
import RoomPage from "../components/RoomPage";
import ThreadList from "../components/ThreadList";
import ThreadPane from "../components/ThreadPane";
import { parseThreadNumber, roomPath, withListParams } from "../utils/roomLinks";
import { parseFocus } from "../utils/threadListParams";
import classes from "./RoomHome.module.css";

/** The conversation pane's body, by route: the new-thread form, one thread, or — with none open — a prompt. */
function PaneBody({ room, isNew, numberParam }: { room: RoomResponse; isNew: boolean; numberParam: string | undefined }) {
  const { t } = useTranslation();
  if (isNew) return <NewThreadForm room={room} />;
  if (numberParam !== undefined) return <ThreadPane room={room} numberParam={numberParam} />;
  return <EmptyState icon={IconMessages} label={t("threads.pane.none")} hint={t("threads.pane.noneHint")} />;
}

/**
 * `/rooms/:id`, `/rooms/:id/threads/new` and `/rooms/:id/threads/:number` — one page, the room's Threads tab: the header and tabs, then
 * the thread list beside the pane the route names (the layout and its container breakpoint: `RoomHome.module.css`). In a narrow main area it is
 * master-detail: the list at the room's own route, the pane on the thread routes with a "Back to threads" link. `?focus=1` (the pane's
 * "Focus on this thread" toggle) hides the list at every width so the conversation takes the main area; it is read on a thread route only
 * (the room's own route and the new-thread form ignore it), and the list stays mounted, so leaving the focus view loses nothing.
 */
export default function RoomHome({ isNew = false }: { isNew?: boolean }) {
  const { t } = useTranslation();
  const { id, number: numberParam } = useParams();
  const { search } = useLocation();
  const inPane = isNew || numberParam !== undefined;
  // An open thread fills the viewport below the room header (its conversation scrolls inside, its composer stays in reach); the list and the form do not.
  const bound = numberParam !== undefined && !isNew;
  const focusView = numberParam !== undefined && parseFocus(new URLSearchParams(search));
  return (
    <RoomPage idParam={id} tab="home" fill={bound}>
      {(room) => (
        <div className={classes.root}>
          <div className={classes.layout} data-pane={inPane ? "thread" : "list"} data-focus={focusView || undefined} data-bound={bound || undefined}>
            <div className={classes.list} role={bound ? "region" : undefined} aria-label={bound ? t("threads.listAria") : undefined} tabIndex={bound ? 0 : undefined}>
              <ThreadList room={room} activeNumber={parseThreadNumber(numberParam)} />
            </div>
            <div className={classes.pane}>
              {inPane && (
                <Group className={classes.back}>
                  <Anchor component={RouterLink} to={withListParams(roomPath(room.id), search)} size="sm">
                    <Group gap={4} wrap="nowrap" component="span">
                      <IconArrowLeft size={14} />
                      {t("threads.back")}
                    </Group>
                  </Anchor>
                </Group>
              )}
              <PaneBody room={room} isNew={isNew} numberParam={numberParam} />
            </div>
          </div>
        </div>
      )}
    </RoomPage>
  );
}
