import { useTranslation } from "react-i18next";
import UnavailablePage from "../components/UnavailablePage";

/**
 * The authenticated catch-all (v2.22.0) — before it, an unmatched URL matched no route at
 * all, so the Shell layout never mounted and the user got a completely blank document.
 * Registered as the last `path="*"` child of the Shell route; deliberately not feature-gated.
 */
export default function NotFound() {
  const { t } = useTranslation();
  return <UnavailablePage title={t("common.notFound.title")} description={t("common.notFound.message")} />;
}
