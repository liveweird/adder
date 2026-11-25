import type { TFunction } from "i18next";
import type { ThreadEvent } from "../api/threads";
import { describeEvent, eventLines } from "../utils/threadEvents";
import { fillSlots, slot } from "../utils/slots";
import type { TimelineItem } from "./EventTimeline";
import PersonLink from "./PersonLink";

function eventTitle(event: ThreadEvent, t: TFunction) {
  if (event.type !== "OWNER_CHANGED") return describeEvent(event, t);
  const fromId = Number(event.params.fromId);
  const toId = Number(event.params.toId);
  if (!Number.isInteger(fromId) || fromId < 1 || !Number.isInteger(toId) || toId < 1) return describeEvent(event, t);
  return fillSlots(t("threads.event.ownerChanged", { from: slot("from"), to: slot("to") }), {
    from: <PersonLink id={fromId} name={event.params.fromName || event.params.fromId} inherit />,
    to: <PersonLink id={toId} name={event.params.toName || event.params.toId} inherit />,
  });
}

export function presentThreadEvent(event: ThreadEvent, t: TFunction): TimelineItem {
  return {
    key: event.id,
    title: eventTitle(event, t),
    lines: eventLines(event, t),
    who: event.actorDeleted
      ? t("threads.history.userDeleted", { name: event.actorName })
      : <PersonLink id={event.actorId} name={event.actorName} quiet inherit />,
    when: event.createdAt,
  };
}
