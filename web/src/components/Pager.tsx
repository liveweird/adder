import { useTranslation } from "react-i18next";
import { Pagination, type PaginationProps } from "@mantine/core";

/**
 * The one page-number control: Mantine's `Pagination` with its icon-only first/previous/next/last controls named
 * (`common.table.*Page`; without names they fail WCAG 4.1.2 and the e2e axe sweep pins it). `PaginationBar` wraps it with the
 * count and the page-size picker; a narrow column (the notification drawer, a thread's history) uses it alone, smaller and
 * without the edge buttons.
 */
export default function Pager({ page, pages, onChange, ...rest }: { page: number; pages: number; onChange: (page: number) => void } & Omit<PaginationProps, "value" | "total" | "onChange">) {
  const { t } = useTranslation();
  return <Pagination value={page} total={pages} onChange={onChange} getControlProps={(control) => ({ "aria-label": t(`common.table.${control}Page`) })} {...rest} />;
}
