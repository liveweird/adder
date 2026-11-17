import { useDisclosure } from "@mantine/hooks";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useBeforeUnloadGuard, type DirtyReader } from "./useBeforeUnloadGuard";

/**
 * The cancel guard for forms (Lettuce's explicit-cancel half, ported): `requestCancel` navigates to `to` straight away while
 * the form is clean and opens a discard confirm once it is dirty; `useBeforeUnloadGuard` covers the browser's own reload/close.
 * `confirmProps` spread onto `ConfirmActionModal` render the confirm ("Keep editing" / "Discard"; Discard leaves for `to`).
 * There is no in-app route blocker: Adder runs a plain `BrowserRouter`, so a sidebar click is not intercepted.
 * Post-save navigations should not go through `requestCancel`.
 */
export function useDiscardGuard({
  isDirty,
  to,
  title,
  message,
}: {
  /** Whether leaving now would lose work — a value or a fresh reader (a payload compare). */
  isDirty: DirtyReader;
  /** Where Cancel goes. */
  to: string;
  /** The confirm's copy; defaults to the generic `common.discard.*` pair. */
  title?: string;
  message?: string;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [opened, { open, close }] = useDisclosure(false);
  const dirty = useBeforeUnloadGuard(isDirty);

  return {
    requestCancel: () => {
      if (dirty()) open();
      else void navigate(to);
    },
    confirmProps: {
      opened,
      onClose: close,
      title: title ?? t("common.discard.title"),
      message: message ?? t("common.discard.message"),
      cancelLabel: t("common.action.keepEditing"),
      confirmLabel: t("common.action.discard"),
      onConfirm: () => {
        close();
        void navigate(to);
      },
    },
  };
}
