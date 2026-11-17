// The changelog is a build-time artifact: entries are authored here (newest first) and bundled
// into the SPA, so it can only change with a deploy — never at runtime. The newest entry's
// version must equal APP_VERSION in ./version.ts (the shell's eager import — this file stays
// out of the main bundle): a release adds the entry AND bumps that literal; entries.test.ts
// pins the pair. Bodies are markdown, one per language (content, not chrome — hence not in
// locales/); keep the phrases tests assert on in plain text runs, and follow the Polish style
// conventions (inclusive slash forms, active voice).
interface ChangelogEntry {
  version: string;
  /** Release date, YYYY-MM-DD. Keep the array strictly descending by date. */
  date: string;
  /** Markdown body, English. */
  en: string;
  /** Markdown body, Polish. */
  pl: string;
}

export const CHANGELOG: readonly ChangelogEntry[] = [
  {
    version: "0.1.0",
    date: "2026-11-17",
    en: `**Adder 0.1.0: the foundation.**

- Sign in with your email and password, with an emailed one-time code as the second step (email MFA), and reset a forgotten password by email.
- Administrators manage users, with their feature flags and language; every user can change their own password.
- Teams, each with an optional manager.
- Light and dark themes, in English and Polish.
- An administrator's telemetry panel with the memory, storage, request and JVM figures of the server.
- The olive Adder brand and the Strike logo. Rooms and threads arrive in a later version.`,
    pl: `**Adder 0.1.0: fundament.**

- Zaloguj się adresem e-mail i hasłem, z jednorazowym kodem wysłanym e-mailem jako drugim krokiem (MFA e-mailem), a zapomniane hasło zresetuj e-mailem.
- Administratorzy/rki zarządzają użytkownikami/czkami, ich flagami funkcji i językiem; każda osoba może zmienić własne hasło.
- Zespoły, każdy z opcjonalnym kierownikiem/czką.
- Jasny i ciemny motyw, po angielsku i po polsku.
- Panel telemetrii dla administratorów/rek z danymi serwera o pamięci, przestrzeni dyskowej, żądaniach i JVM.
- Oliwkowa marka Adder i logo Strike. Pokoje i wątki pojawią się w kolejnej wersji.`,
  },
];
