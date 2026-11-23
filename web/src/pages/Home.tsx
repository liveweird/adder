import { Button, Stack } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconArrowRight } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { getHome } from "../api/home";
import ErrorAlert from "../components/ErrorAlert";
import HomeDashboard from "../components/HomeDashboard";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import { homeKey } from "../utils/roomKeys";
import { roomsPath } from "../utils/roomLinks";
import classes from "./Home.module.css";

/** The signed-in landing page: the caller's four small, personal work queues. */
export default function Home() {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: homeKey, queryFn: getHome });

  return (
    <Stack gap="xl" className={classes.root}>
      <PageHeader
        title={t("home.title")}
        actions={
          <Button component={RouterLink} to={roomsPath} variant="default" rightSection={<IconArrowRight size={16} />}>
            {t("home.allRooms")}
          </Button>
        }
      />
      {query.isLoading && <LoadingBlock mih={240} />}
      {query.isError && <ErrorAlert error={query.error} title={t("home.loadFailed")} />}
      {query.data && <HomeDashboard data={query.data} />}
    </Stack>
  );
}
