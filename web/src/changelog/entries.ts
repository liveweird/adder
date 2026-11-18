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
    version: "0.2.0",
    date: "2026-11-18",
    en: `**Adder 0.2.0: rooms and workflows.**

- Create a room for a piece of work and become its owner. Rooms are members-only: add people one by one, or a whole team at once.
- An owner manages the room, and a room always keeps at least one owner. Archive a room to make it read-only; unarchive it when work resumes.
- Every room has its own workflow: statuses with a colour, an open or closed category and an order, plus typed fields. Owners edit it in place.
- Administrators curate the workflow templates a new room starts from. Default and Incident are built in, in English and Polish.
- Your rooms sit in the navigation, most recently active first, with archived rooms behind a switch.
- Teams now have members, managed by an administrator or the team's manager.
- Threads and messages arrive in the next version.`,
    pl: `**Adder 0.2.0: pokoje i przepływy pracy.**

- Utwórz pokój do konkretnej pracy i zostań jego właścicielem/ką. Pokoje są tylko dla członków i członkiń: dodawaj osoby pojedynczo albo cały zespół naraz.
- Właściciel/ka zarządza pokojem, a pokój zawsze ma co najmniej jednego właściciela/kę. Zarchiwizuj pokój, aby był tylko do odczytu; przywróć go, gdy praca wraca.
- Każdy pokój ma własny przepływ pracy: statusy z kolorem, kategorią (otwarty lub zamknięty) i kolejnością oraz typowane pola. Właściciele/ki edytują go na miejscu.
- Administratorzy/rki prowadzą szablony przepływów, od których zaczyna nowy pokój. Wbudowane są Default i Incident, po angielsku i po polsku.
- Twoje pokoje są w nawigacji, od ostatnio aktywnych, a zarchiwizowane pod przełącznikiem.
- Zespoły mają teraz członków i członkinie, którymi zarządza administrator/ka albo kierownik/czka zespołu.
- Wątki i wiadomości pojawią się w kolejnej wersji.`,
  },
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
