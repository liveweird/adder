import { useMutation, useQueryClient } from "@tanstack/react-query";
import { IconBell, IconBellOff } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { setThreadMute } from "../api/threads";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { isAccessRefusal } from "../utils/roomAccess";
import { roomKeys } from "../utils/roomKeys";
import { saveErrorMessage } from "../utils/saveError";
import HeaderIconButton from "./HeaderIconButton";

export default function ThreadMuteButton({ roomId, number, muted, onError }: {
  roomId: number;
  number: number;
  muted: boolean;
  onError: (message: string | null) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () => setThreadMute(roomId, number, !muted),
    onMutate: () => onError(null),
    onSuccess: () => refreshQueriesAfterMutation(queryClient, roomKeys.threadDetail(roomId, number)),
    onError: async (error) => {
      onError(saveErrorMessage(error, t, { failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" }));
      if (isAccessRefusal(error)) await refreshQueriesAfterMutation(queryClient, roomKeys.all);
    },
  });

  return <HeaderIconButton
    label={t("threads.mute")}
    pressed={muted}
    loading={mutation.isPending}
    icon={muted ? <IconBellOff size={16} /> : <IconBell size={16} />}
    onClick={() => mutation.mutate()}
  />;
}
