/** The ONE place the people route family is spelled out — never hand-assemble a profile URL. */
export const peoplePath = "/people";
export const personPath = (id: number) => `${peoplePath}/${id}`;

/**
 * The numeric id of a `:id` route parameter, or null when it is not a plain positive int32 in ASCII digits with no leading zero (`12abc`, `0`,
 * `-1`, `007`): such a person cannot exist, and one address per person keeps the profile's cache and links unambiguous.
 */
export function parsePersonId(param: string | undefined): number | null {
  if (param === undefined || !/^[1-9]\d{0,9}$/.test(param)) return null;
  const id = Number(param);
  return id <= 2_147_483_647 ? id : null;
}
