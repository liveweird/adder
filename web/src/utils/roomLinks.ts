/** The ONE place the room route family is spelled out — never hand-assemble a room URL. */
export const roomsPath = "/rooms";
export const newRoomPath = `${roomsPath}/new`;
export const roomPath = (id: number) => `${roomsPath}/${id}`;
export const roomMembersPath = (id: number) => `${roomPath(id)}/members`;
export const roomSettingsPath = (id: number) => `${roomPath(id)}/settings`;

/** The numeric id of a `:id` route parameter, or null when it is not a plain positive int32 (`12abc`, `1e3`, `0`, `-1`): such a room cannot exist. */
export function parseRoomId(param: string | undefined): number | null {
  if (param === undefined || !/^\d+$/.test(param)) return null;
  const id = Number(param);
  return id >= 1 && id <= 2_147_483_647 ? id : null;
}
