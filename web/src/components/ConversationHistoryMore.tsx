import { Button, Center } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { useConversationHistory } from "../hooks/useConversationHistory";

export default function ConversationHistoryMore({ query }: { query: ReturnType<typeof useConversationHistory>["query"] }) {
  const { t } = useTranslation();
  if (!query.hasNextPage && !query.isError) return null;
  return (
    <Center py="xs">
      <Button
        variant="subtle"
        size="compact-sm"
        aria-busy={query.isFetching}
        onClick={() => {
          if (query.isFetching) return;
          if (query.isError && !query.isFetchNextPageError) void query.refetch();
          else void query.fetchNextPage();
        }}
      >
        {t(query.isError ? "threads.history.retry" : "threads.history.more")}
      </Button>
    </Center>
  );
}
