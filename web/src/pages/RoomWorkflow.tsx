import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { Button, Group } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useAdmin } from "../auth";
import { ApiError } from "../api/http";
import { getRoomWorkflow, type RoomResponse } from "../api/rooms";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import RoomPage from "../components/RoomPage";
import RoomWorkflowForm from "../components/RoomWorkflowForm";
import { canManageRoom } from "../utils/roomAccess";
import { toRoomWorkflowDraft, type RoomWorkflow, type WorkflowDraft } from "../utils/workflowForm";

/**
 * A seed paired with the revision it was read at (the `X-Expected-Revision` of the save), whether the caller could edit when it was
 * taken, a generation that re-keys the form, and whether the new form takes focus (a re-seed the user asked for or caused).
 */
type LoadedSeed = { seed: WorkflowDraft; revision: number; editable: boolean; generation: number; focus: boolean };

/** The page body of one loaded room: reads the workflow, seeds the form once and re-seeds it on demand. */
function WorkflowBody({ room }: { room: RoomResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const admin = useAdmin();
  const editable = canManageRoom(room, admin) && !room.archived;
  const [loaded, setLoaded] = useState<LoadedSeed | null>(null);
  // The words of a failed save or reload: here, not in the form, because a refusal that turns the editor read-only re-seeds it.
  const [error, setError] = useState<string | null>(null);

  // Its own key (not the Threads view's shared `["rooms", "workflow", id]`), so that cache never seeds a draft: a visit
  // starts from a fresh read (`gcTime: 0`). Someone who can edit never refetches on focus (it would only be thrown away); a reader
  // does, so what they see follows the room.
  const readQuery = { queryKey: ["rooms", "workflow-edit", room.id], queryFn: () => getRoomWorkflow(room.id), gcTime: 0, refetchOnWindowFocus: !editable };
  const { data, isLoading, error: readError, refetch } = useQuery({ ...readQuery, retry: false });

  const seedOf = (workflow: RoomWorkflow, focus: boolean, generation: number): LoadedSeed => ({
    seed: toRoomWorkflowDraft(workflow),
    revision: workflow.revision,
    editable,
    generation,
    focus,
  });
  // Derived during render (the guarded idiom): the first read seeds the form. A background refetch is ignored while the caller
  // can edit (it would rebase the draft). Once they cannot (demoted, or the room was archived behind the page) the form is
  // re-seeded at once, so the read-only view shows the stored workflow, not the unsaved draft; and a reader who cannot edit sees a
  // newer revision when one arrives.
  if (data && loaded === null) setLoaded(seedOf(data, false, 1));
  else if (data && loaded && !editable && (loaded.editable || data.revision !== loaded.revision)) setLoaded(seedOf(data, loaded.editable, loaded.generation + 1));

  // The workflow answering 404 means the room is gone or the caller no longer reads it: re-read the room, which then shows not-found.
  const gone = readError instanceof ApiError && readError.status === 404;
  useEffect(() => {
    if (gone) void queryClient.invalidateQueries({ queryKey: ["rooms", "detail", room.id] });
  }, [gone, queryClient, room.id]);

  async function reseed() {
    const fresh = await queryClient.fetchQuery({ ...readQuery, staleTime: 0 });
    setLoaded((previous) => seedOf(fresh, true, (previous?.generation ?? 0) + 1));
  }

  if (loaded === null) {
    if (isLoading) return <LoadingBlock />;
    return (
      <>
        <ErrorAlert error={readError} title={t("rooms.workflow.loadFailed")} />
        <Group justify="flex-end">
          <Button variant="default" onClick={() => void refetch()}>
            {t("common.action.retry")}
          </Button>
        </Group>
      </>
    );
  }
  return (
    <RoomWorkflowForm
      key={loaded.generation}
      room={room}
      seed={loaded.seed}
      revision={loaded.revision}
      editable={editable}
      focusOnMount={loaded.focus}
      error={error}
      onError={setError}
      onReseed={reseed}
    />
  );
}

/**
 * `/rooms/:id/workflow`: the room's own copy of its workflow, in the editor. The room's OWNER or an ADMIN edits it (statuses, fields,
 * the retire dialog that moves a removed status's threads) and saves it as a whole document under the revision the form was
 * seeded with; every other member reads it, and so does everyone while the room is archived.
 */
export default function RoomWorkflow() {
  const { id } = useParams();
  // Keyed by the room: another room's page never inherits this one's seed, draft or revision.
  return (
    <RoomPage idParam={id} tab="workflow">
      {(room) => <WorkflowBody key={room.id} room={room} />}
    </RoomPage>
  );
}
