/**
 * The `mailto:` URL of one address, with no query: the address percent-encoded (so a `?`, `&`, `#` or space in it cannot start a
 * query or a fragment, or smuggle a header such as `cc=`) except `@`, which stays readable. Append `?subject=…&body=…` for a draft.
 * The ONE place an email becomes a `mailto:` link (the onboarding draft and the profile page).
 */
export function mailtoHref(email: string): string {
  return `mailto:${encodeURIComponent(email).replace(/%40/g, "@")}`;
}
