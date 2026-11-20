import { useTranslation } from "react-i18next";
import { Group, Select, Text } from "@mantine/core";
import { PAGE_SIZE_OPTIONS } from "../hooks/usePagedSort";
import Pager from "./Pager";

/**
 * The shared list footer: total count, rows-per-page picker, and the pager. A list whose page size is fixed (the thread rail: 50, which
 * is none of the picker's sizes, and the rail is too narrow for the picker) omits `onPageSizeChange`, and the picker with it.
 */
export default function PaginationBar({
  total,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
}: {
  total: number;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange?: (size: number) => void;
}) {
  const { t } = useTranslation();
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  return (
    <Group justify="space-between" align="center">
      <Text size="sm" c="dimmed">
        {t("common.table.total", { count: total })}
      </Text>
      <Group gap="sm" align="center">
        {onPageSizeChange && (
          <Select
            size="xs"
            aria-label={t("common.table.rowsPerPage")}
            data={PAGE_SIZE_OPTIONS.map((n) => ({
              value: String(n),
              label: t("common.table.perPage", { count: n }),
            }))}
            value={String(pageSize)}
            onChange={(v) => {
              if (!v) return;
              onPageSizeChange(Number(v));
            }}
            allowDeselect={false}
            w={110}
          />
        )}
        <Pager page={page} pages={totalPages} onChange={onPageChange} siblings={1} withEdges />
      </Group>
    </Group>
  );
}
