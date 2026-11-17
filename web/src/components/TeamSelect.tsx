import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { listTeams } from "../api/teams";
import { OPTION_PAGE_SIZE } from "../hooks/usePagedSort";
import SearchPicker, { type PickedOption } from "./SearchPicker";

/**
 * A searchable, clearable picker over the teams registry (`GET /teams` is open to every signed-in user): a room's "add a whole team".
 * Options read the team's name; the caller holds the pick (its label survives later searches).
 */
export default function TeamSelect({
  label,
  description,
  picked,
  onPick,
  disabled,
  error,
}: {
  /** Replaces the default "Team" field label where the control means something more specific. */
  label?: string;
  description?: string;
  picked: PickedOption | null;
  onPick: (picked: PickedOption | null) => void;
  disabled?: boolean;
  /** A field error (a validation or a server answer the form maps onto this control). */
  error?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <SearchPicker
      label={label ?? t("common.field.team")}
      description={description}
      placeholder={t("common.picker.team.placeholder")}
      clearLabel={t("common.picker.team.clear")}
      noMatchLabel={t("common.picker.team.noMatch")}
      queryKey={["teams", "picker"]}
      search={async (term) => {
        const page = await listTeams({ page: 1, pageSize: OPTION_PAGE_SIZE, sort: "name", name: term || undefined });
        return page.items.map((team) => ({ value: String(team.id), label: team.name }));
      }}
      picked={picked}
      onPick={onPick}
      disabled={disabled}
      error={error}
    />
  );
}
