import { Stack } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { searchRooms } from "../api/search";
import PageHeader from "../components/PageHeader";
import SearchBody from "../components/SearchBody";
import { searchAllKey } from "../utils/roomKeys";

/** `/search`: search the threads and messages of every room the caller is a member of (an ADMIN's too: membership is the scope). */
export default function Search() {
  const { t } = useTranslation();
  return (
    <Stack gap="md">
      <PageHeader title={t("appShell.nav.search")} />
      <SearchBody label={t("search.all.label")} baseKey={searchAllKey} fetchPage={searchRooms} showRoom />
    </Stack>
  );
}
