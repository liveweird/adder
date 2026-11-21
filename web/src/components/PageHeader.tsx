import { type ReactNode } from "react";
import { ActionIcon, Anchor, Group, Stack, Text, Title, Tooltip } from "@mantine/core";
import { IconArrowLeft } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import { useDocumentTitle } from "../hooks/useDocumentTitle";

/**
 * Every page's first row (v1.19.0): the title (an `order={2}` heading — the name tests and
 * the e2e accessibility scan locate pages by), an optional dimmed description, an optional
 * back link above the title, the page's primary action(s) right-aligned on the SAME row,
 * and an optional toolbar row underneath (the list pages' filter/lens/kind row). One
 * component, so a "New …" button can only ever be in one place. It also titles the browser tab ("{title} · Adder", or
 * `documentTitle` when the tab needs more than the heading says — a room's tabs): every page with a header has a
 * tab title, with no per-page call to forget.
 *
 * `compact` (a room's header, 1.2 D4's sibling): the same parts in ONE wrapping row — an icon-only back link (its `aria-label` is the link text, so a
 * by-name locator still finds it), the title (still the h2, drawn at h4 size), the description inline, the toolbar, and the actions pushed to the
 * right edge. A description in a compact header may hold a badge, so it sits in a `div`, never a `p`.
 */
export default function PageHeader({
  title,
  documentTitle,
  description,
  backTo,
  actions,
  toolbar,
  compact = false,
}: {
  title: string;
  documentTitle?: string;
  description?: ReactNode;
  backTo?: { to: string; label: string };
  actions?: ReactNode;
  toolbar?: ReactNode;
  compact?: boolean;
}) {
  useDocumentTitle(documentTitle ?? title);
  if (compact) {
    return (
      <Group gap="sm" align="center" wrap="wrap">
        {backTo && (
          <Tooltip label={backTo.label}>
            <ActionIcon component={RouterLink} to={backTo.to} aria-label={backTo.label} variant="subtle">
              <IconArrowLeft size={16} />
            </ActionIcon>
          </Tooltip>
        )}
        <Title order={2} size="h4" miw={0} style={{ overflowWrap: "anywhere" }}>
          {title}
        </Title>
        {description && (
          <Text component="div" c="dimmed" size="sm">
            {description}
          </Text>
        )}
        {toolbar}
        {actions && (
          <Group gap="xs" wrap="wrap" ml="auto">
            {actions}
          </Group>
        )}
      </Group>
    );
  }
  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-start" wrap="wrap" gap="sm">
        <Stack gap={2}>
          {backTo && (
            <Anchor component={RouterLink} to={backTo.to} size="xs" c="dimmed">
              <Group gap={4} wrap="nowrap" component="span">
                <IconArrowLeft size={12} />
                {backTo.label}
              </Group>
            </Anchor>
          )}
          <Title order={2}>{title}</Title>
          {description && (
            <Text c="dimmed" size="sm">
              {description}
            </Text>
          )}
        </Stack>
        {actions && (
          <Group gap="sm" wrap="wrap">
            {actions}
          </Group>
        )}
      </Group>
      {toolbar}
    </Stack>
  );
}
