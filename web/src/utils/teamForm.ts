import type { TFunction } from "i18next";
import type { TeamCreateBody, TeamResponse, TeamUpdateBody } from "../api/teams";
import { descriptionRule, nameRule } from "./formRules";
import { saveErrorMessage } from "./saveError";

// Server limits (teams/Team.kt) mirrored client-side.
export const MAX_TEAM_NAME_LENGTH = 100;
export const MAX_TEAM_DESCRIPTION_LENGTH = 500;
const MIN_TEAM_HANDLE_LENGTH = 2;
export const MAX_TEAM_HANDLE_LENGTH = 30;

// The handle grammar and the reserved words (teams/Team.kt, chat-model.md "Group mentions"): never folded, so an uppercase letter fails.
const TEAM_HANDLE_PATTERN = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;
const RESERVED_TEAM_HANDLES = ["everyone", "watchers", "observers", "here", "all", "channel", "room", "team"];

export type TeamFormValues = {
  name: string;
  description: string;
  /** The optional mention handle; blank = none. */
  handle: string;
  /** The picked manager's user id (a string, the Select's value type); null = no manager. */
  managerId: string | null;
  /** The picked manager's display label, kept beside the id so it survives later searches. */
  managerLabel: string | null;
};

export const EMPTY_TEAM_FORM: TeamFormValues = { name: "", description: "", handle: "", managerId: null, managerLabel: null };

export function toTeamFormValues(team: TeamResponse): TeamFormValues {
  return {
    name: team.name,
    description: team.description ?? "",
    handle: team.handle ?? "",
    managerId: team.managerId === null ? null : String(team.managerId),
    managerLabel: team.managerName,
  };
}

/** Validation rules shared by the create and edit modal (mirrors the server's checks). */
export function teamFormValidation(t: TFunction) {
  return {
    name: nameRule(t, "teams.validation.nameLength", MAX_TEAM_NAME_LENGTH),
    description: descriptionRule(t, "teams.validation.descriptionLength", MAX_TEAM_DESCRIPTION_LENGTH),
    handle: (value: string) => {
      const handle = value.trim();
      if (handle === "") return null;
      if (handle.length < MIN_TEAM_HANDLE_LENGTH || handle.length > MAX_TEAM_HANDLE_LENGTH || !TEAM_HANDLE_PATTERN.test(handle)) {
        return t("teams.validation.handleFormat");
      }
      return RESERVED_TEAM_HANDLES.includes(handle) ? t("teams.validation.handleReserved") : null;
    },
  };
}

/**
 * The wire body: a blank description or handle travels as null (the server stores NULL, never ""); a PUT is a
 * full replace, so no manager and no handle are explicit nulls.
 */
export function toTeamBody(values: TeamFormValues): TeamCreateBody & TeamUpdateBody {
  const description = values.description.trim();
  const handle = values.handle.trim();
  return {
    name: values.name.trim(),
    description: description === "" ? null : description,
    handle: handle === "" ? null : handle,
    managerId: values.managerId === null ? null : Number(values.managerId),
  };
}

/** The team save vocabulary — 409 is about the name field and is handled by the caller. */
export function teamSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "teams.saveForbidden",
    notFound: "teams.saveGone",
    invalid: "teams.saveInvalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
