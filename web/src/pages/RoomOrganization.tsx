import { useMemo, useState } from "react";
import { Button, Stack, Text } from "@mantine/core";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconArrowLeft } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { getRoomOrganization, replaceRoomOrganization, type RoomOrganization } from "../api/roomOrganization";
import { ApiError } from "../api/http";
import { listRooms } from "../api/rooms";
import ConfirmActionModal from "../components/ConfirmActionModal";
import ErrorAlert from "../components/ErrorAlert";
import FormErrorAlert from "../components/FormErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import RevisionConflictAlert from "../components/RevisionConflictAlert";
import RoomOrganizationEditor from "../components/RoomOrganizationEditor";
import { useDiscardGuard } from "../hooks/useDiscardGuard";
import { isProblem, PROBLEM_TYPES } from "../utils/problems";
import { MAX_ROOM_PLACEMENTS, organizationDraft, type RoomOrganizationDraft } from "../utils/roomOrganizationDraft";
import { roomOrganizationKey, roomKeys } from "../utils/roomKeys";
import { roomsPath } from "../utils/roomLinks";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import classes from "./RoomOrganization.module.css";

const PAGE_SIZE = 100;

function sameDraft(left: RoomOrganizationDraft | null, right: RoomOrganizationDraft | null): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

export default function RoomOrganizationPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const organization = useQuery({ queryKey: roomOrganizationKey, queryFn: getRoomOrganization });
  const rooms = useInfiniteQuery({
    queryKey: [...roomKeys.list, "organization"],
    initialPageParam: 1,
    queryFn: ({ pageParam }) => listRooms({ page: pageParam, pageSize: PAGE_SIZE, member: true, sort: "personalOrder" }),
    getNextPageParam: (last, pages) => pages.length * PAGE_SIZE < last.total ? pages.length + 1 : undefined,
  });
  const loadedRooms = useMemo(() => {
    const items = rooms.data?.pages.flatMap((page) => page.items) ?? [];
    return [...new Map(items.map((room) => [room.id, room])).values()];
  }, [rooms.data]);
  const [editor, setEditor] = useState<{
    source: RoomOrganization | null;
    baseline: RoomOrganizationDraft | null;
    draft: RoomOrganizationDraft | null;
  }>({ source: null, baseline: null, draft: null });
  const { source, baseline, draft } = editor;
  const [conflict, setConflict] = useState(false);
  const [reloading, setReloading] = useState(false);
  const dirty = !sameDraft(draft, baseline);
  const back = useDiscardGuard({ isDirty: dirty, to: roomsPath });

  const save = useMutation({
    mutationFn: () => {
      if (!source || !draft) throw new Error("Room organization is not ready");
      if (draft.rooms.length > MAX_ROOM_PLACEMENTS) throw new Error("Room organization has too many placements");
      return replaceRoomOrganization({
        groups: draft.groups.map((group) => ({ ...group, name: group.name.trim() })),
        rooms: draft.rooms,
      }, source.revision);
    },
    onSuccess: async (saved) => {
      await queryClient.cancelQueries({ queryKey: roomOrganizationKey });
      const next = organizationDraft(saved);
      setEditor({ source: saved, baseline: next, draft: next });
      setConflict(false);
      queryClient.setQueryData(roomOrganizationKey, saved);
      await refreshQueriesAfterMutation(queryClient, roomKeys.rail, roomKeys.list);
      showSuccessToast(t("rooms.organization.saved"));
    },
    onError: (error) => {
      if (isProblem(error, PROBLEM_TYPES.revisionConflict)) setConflict(true);
      else if (error instanceof ApiError && error.status === 400) void organization.refetch();
    },
  });

  const mayAdopt = organization.data && (
    source === null || (!dirty && !save.isPending && !conflict && organization.data !== source)
  );
  if (mayAdopt) {
    const next = organizationDraft(organization.data);
    setEditor({ source: organization.data, baseline: next, draft: next });
  }

  const reload = async () => {
    setReloading(true);
    const result = await organization.refetch();
    setReloading(false);
    if (!result.isSuccess) return;
    const next = organizationDraft(result.data);
    setEditor({ source: result.data, baseline: next, draft: next });
    setConflict(false);
    save.reset();
    showSuccessToast(t("rooms.organization.reloaded"));
  };

  const saveError = save.isError && !conflict
    ? saveErrorMessage(save.error, t, {
      retryAfter: true,
      invalid: "rooms.organization.saveFailed",
      failedStatus: "common.error.actionFailedStatus",
      failed: "rooms.organization.saveFailed",
    })
    : null;
  const total = rooms.data?.pages[0]?.total ?? 0;

  return (
    <Stack gap="lg" className={classes.page}>
      <PageHeader
        title={t("rooms.organization.title")}
        actions={
          <Button variant="default" leftSection={<IconArrowLeft size={16} aria-hidden="true" />} onClick={back.requestCancel}>
            {t("rooms.organization.back")}
          </Button>
        }
      />
      <Text c="dimmed" className={classes.intro}>{t("rooms.organization.description")}</Text>
      {organization.isLoading && <LoadingBlock mih={220} />}
      {organization.isError && <ErrorAlert error={organization.error} title={t("rooms.organization.loadFailed")} />}
      {rooms.isError && <ErrorAlert error={rooms.error} title={t("rooms.organization.roomsLoadFailed")} />}
      {conflict && (
        <RevisionConflictAlert
          onReload={() => void reload()}
          reloading={reloading}
          title={t("rooms.organization.conflictTitle")}
          message={t("rooms.organization.conflictMessage")}
          reloadLabel={t("rooms.organization.reload")}
        />
      )}
      {saveError && <FormErrorAlert title={t("rooms.organization.saveFailed")}>{saveError}</FormErrorAlert>}
      {draft && (
        <RoomOrganizationEditor
          rooms={loadedRooms}
          draft={draft}
          onChange={(next) => {
            setEditor((current) => ({ ...current, draft: next }));
            save.reset();
          }}
          onSave={() => save.mutate()}
          onDiscard={() => {
            setEditor((current) => ({ ...current, draft: baseline }));
            save.reset();
          }}
          saving={save.isPending}
          reloading={reloading}
          saveBlocked={conflict}
          dirty={dirty}
        />
      )}
      {rooms.hasNextPage && (
        <Button
          variant="default"
          className={classes.loadMore}
          loading={rooms.isFetchingNextPage}
          onClick={() => void rooms.fetchNextPage()}
        >
          {t("rooms.organization.loadMore")}
        </Button>
      )}
      {total > 0 && <Text size="xs" c="dimmed" ta="center">{t("rooms.organization.loaded", { count: loadedRooms.length, total })}</Text>}
      <ConfirmActionModal {...back.confirmProps} />
    </Stack>
  );
}
