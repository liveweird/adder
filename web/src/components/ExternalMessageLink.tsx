import { useContext, useEffect, useId, useRef, useState, useSyncExternalStore, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from "react";
import { QueryClientContext } from "@tanstack/react-query";
import { Button, Group, Popover, Stack, Text } from "@mantine/core";
import { IconExternalLink, IconPhoto } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { LinkPreview, MessageLinkSource } from "../api/linkPreviews";
import { cachedLinkPreview, loadLinkPreview, previewSessionIdentity } from "../utils/linkPreviewCache";
import { subscribeLogicalSession } from "../utils/sessionQueryCache";
import classes from "./ExternalMessageLink.module.css";

type Props = { destination: URL; source?: MessageLinkSource; children: ReactNode; title?: string };
type Overlay = "confirmation" | "preview" | null;

function PreviewContent({ preview }: { preview: LinkPreview }) {
  const { t } = useTranslation();
  return (
    <Stack gap={6} className={classes.preview}>
      {preview.title && <Text size="sm" fw={600} lineClamp={2}>{preview.title}</Text>}
      {preview.image && (
        <img
          className={classes.image}
          src={`data:${preview.image.mediaType};base64,${preview.image.data}`}
          width={preview.image.width}
          height={preview.image.height}
          alt={t("threads.linkPreview.imageAlt")}
        />
      )}
    </Stack>
  );
}

function LinkOverlay({ destination, source, children, title, identity }: Props & { identity: string | null }) {
  const { t } = useTranslation();
  const client = useContext(QueryClientContext);
  const [overlay, setOverlay] = useState<Overlay>(null);
  const [preview, setPreview] = useState<LinkPreview | null>();
  const [loading, setLoading] = useState(false);
  const [portalTarget, setPortalTarget] = useState<HTMLElement>();
  const overlayId = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const restoringFocus = useRef(false);
  const closeTimer = useRef<number | undefined>(undefined);
  const href = destination.href;
  const canPreview = Boolean(client && source && identity && destination.protocol === "https:" && destination.port === "");

  function currentPreview() {
    return client && source && identity ? cachedLinkPreview(client, source, href, identity) : undefined;
  }

  function showCachedPreview() {
    window.clearTimeout(closeTimer.current);
    // A containing dialog owns Escape while pointer movement leaves its focus elsewhere.
    if (trigger.current?.closest('[role="dialog"]')) return;
    const cached = currentPreview();
    if (!cached || cached.kind === "UNAVAILABLE") return;
    setPortalTarget(undefined);
    setPreview(cached);
    setOverlay((current) => current ?? "preview");
  }

  function leavePreview() {
    window.clearTimeout(closeTimer.current);
    closeTimer.current = window.setTimeout(() => setOverlay((current) => current === "preview" ? null : current), 150);
  }

  function confirm() {
    window.clearTimeout(closeTimer.current);
    setPortalTarget(trigger.current?.closest<HTMLElement>('[role="dialog"]') ?? undefined);
    setPreview(currentPreview());
    setOverlay("confirmation");
  }

  function close(restoreFocus = false) {
    window.clearTimeout(closeTimer.current);
    setOverlay(null);
    if (restoreFocus) {
      restoringFocus.current = true;
      trigger.current?.focus({ preventScroll: true });
      restoringFocus.current = false;
    }
  }

  function dismissOnEscape(event: ReactKeyboardEvent) {
    if (overlay === null || event.key !== "Escape" || event.nativeEvent.isComposing) return;
    event.stopPropagation();
    event.preventDefault();
    close(overlay === "confirmation");
  }

  async function load() {
    if (!client || !source || !identity || loading) return;
    setLoading(true);
    try {
      const result = await loadLinkPreview(client, source, href, identity);
      if (identity === previewSessionIdentity()) setPreview(result);
    } catch {
      if (identity === previewSessionIdentity()) setPreview(null);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => () => window.clearTimeout(closeTimer.current), []);
  useEffect(() => {
    if (overlay !== "preview") return;
    const dismiss = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.isComposing) return;
      window.clearTimeout(closeTimer.current);
      setOverlay(null);
    };
    window.addEventListener("keydown", dismiss);
    return () => window.removeEventListener("keydown", dismiss);
  }, [overlay]);

  const confirmation = overlay === "confirmation";
  const available = preview && preview.kind !== "UNAVAILABLE";
  const unavailable = preview === null || preview?.kind === "UNAVAILABLE";
  return (
    <Popover
      opened={overlay !== null}
      onChange={(opened) => { if (!opened) close(); }}
      position="bottom-start"
      width="min(24rem, calc(100vw - 2rem))"
      shadow="md"
      radius="md"
      trapFocus={confirmation}
      closeOnEscape={false}
      withRoles={false}
      portalProps={{ target: portalTarget }}
      floatingStrategy={portalTarget ? "fixed" : "absolute"}
    >
      <Popover.Target>
        <button
          type="button"
          ref={trigger}
          className={classes.trigger}
          title={title}
          aria-haspopup="dialog"
          aria-expanded={confirmation}
          aria-controls={confirmation ? overlayId : undefined}
          aria-describedby={overlay === "preview" ? overlayId : undefined}
          data-mantine-stop-propagation={overlay !== null ? "true" : undefined}
          onClick={confirm}
          onKeyDown={dismissOnEscape}
          onPointerEnter={(event) => { if (event.pointerType !== "touch") showCachedPreview(); }}
          onPointerLeave={leavePreview}
          onFocus={(event) => { if (!restoringFocus.current && event.currentTarget.matches(":focus-visible")) showCachedPreview(); }}
          onBlur={leavePreview}
        >
          {children}
        </button>
      </Popover.Target>
      <Popover.Dropdown
        id={overlayId}
        role={confirmation ? "dialog" : "tooltip"}
        aria-label={t(confirmation ? "threads.linkPreview.confirm" : "threads.linkPreview.preview")}
        onKeyDownCapture={dismissOnEscape}
        onPointerEnter={() => window.clearTimeout(closeTimer.current)}
        onPointerLeave={leavePreview}
      >
        <Stack gap="xs">
          {confirmation ? (
            <>
              <Text fw={650} size="sm">{t("threads.linkPreview.confirm")}</Text>
              <Text size="sm" c="dimmed">{t("threads.linkPreview.leaving")}</Text>
            </>
          ) : <Text fw={650} size="sm">{destination.hostname}</Text>}
          <Text size="xs" dir="ltr" className={classes.destination}>{href}</Text>
          {available && <PreviewContent preview={preview} />}
          {confirmation && (
            <>
              {loading && <Text role="status" size="xs" c="dimmed">{t("threads.linkPreview.loading")}</Text>}
              {unavailable && !loading && <Text role="status" size="xs" c="dimmed">{t("threads.linkPreview.unavailable")}</Text>}
              <Group gap="xs" justify="flex-end">
                {canPreview && (
                  <Button
                    size="compact-sm"
                    variant="light"
                    aria-disabled={loading}
                    data-disabled={loading || undefined}
                    aria-expanded={Boolean(available)}
                    data-mantine-stop-propagation="true"
                    onClick={() => { if (available) setPreview(undefined); else void load(); }}
                    leftSection={<IconPhoto size={15} aria-hidden="true" />}
                  >
                    {t(available ? "threads.linkPreview.hide" : "threads.linkPreview.preview")}
                  </Button>
                )}
                <Button data-autofocus data-mantine-stop-propagation="true" size="compact-sm" variant="default" onClick={() => close(true)}>
                  {t("common.action.cancel")}
                </Button>
                <Button
                  component="a"
                  href={href}
                  target="_blank"
                  rel="noopener noreferrer"
                  data-mantine-stop-propagation="true"
                  size="compact-sm"
                  rightSection={<IconExternalLink size={15} aria-hidden="true" />}
                  onClick={() => close(true)}
                >
                  {t("threads.linkPreview.open")}
                </Button>
              </Group>
            </>
          )}
        </Stack>
      </Popover.Dropdown>
    </Popover>
  );
}

export default function ExternalMessageLink(props: Props) {
  const identity = useSyncExternalStore(subscribeLogicalSession, previewSessionIdentity, () => null);
  const key = JSON.stringify([identity, props.destination.href, props.source]);
  return <LinkOverlay key={key} {...props} identity={identity} />;
}
