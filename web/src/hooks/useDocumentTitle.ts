import { useEffect, useSyncExternalStore } from "react";
import { useTranslation } from "react-i18next";
import { composeTitle, getTitleCount, subscribeTitleCount } from "../utils/titleCount";

/**
 * Sets the browser tab's title to "{title} · Adder" for as long as the calling page is mounted (WCAG 2.4.2: a tab
 * titled "Adder" on every route says nothing about where the reader is), and puts the bare brand back on unmount, so
 * the lazy-chunk fallback between pages shows "Adder", never the last page's name.
 * A page titled with the brand itself (Home) gets the bare brand, never "Adder · Adder". Called once per page, by `PageHeader` and `AuthCard`; the brand is `appShell.brand`, so a language switch re-titles
 * the tab on the next render. While unseen notices are counted (`utils/titleCount.ts`, written by the shell from the bell's total) the title
 * carries "(3) " in front, in the same composer on mount, on change and on the unmount reset.
 */
export function useDocumentTitle(title: string): void {
  const { t } = useTranslation();
  const brand = t("appShell.brand");
  const unseen = useSyncExternalStore(subscribeTitleCount, getTitleCount, () => 0);
  useEffect(() => {
    document.title = composeTitle(title, brand, unseen);
    return () => {
      document.title = composeTitle("", brand, getTitleCount());
    };
  }, [title, brand, unseen]);
}
