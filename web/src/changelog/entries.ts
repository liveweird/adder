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
    version: "0.6.0",
    date: "2026-11-20",
    en: `**Adder 0.6.0: social.**

- Mention a room member with @: start typing a name in the composer and pick the person. The mention shows as their current name, and the thread list can show only the threads that mention you.
- A bell in the header counts your unseen notifications: when someone mentions you, or hands a thread over to you. A click opens the message; "Mark all as seen" clears the count. New notifications arrive live.
- If a notification is still unseen after five minutes, you get one email that sums them up (at most one every 15 minutes). The email names who, the room and the thread number, with a link, and never the message itself. Switch it off per kind under "Notification emails" in your account menu.
- React to a message with an emoji. Each reaction shows its count and the first few people; press it again to take yours back.
- Pin threads and messages to a room. The pinned panel in the room header lists them for everyone in the room.
- See what is new: a dot in the rooms rail for a room with new activity, unread counts in the thread list, and a "New" line in the conversation where you stopped reading, with a jump to it. When you are scrolled up, a button says how many new messages wait below.`,
    pl: `**Adder 0.6.0: społeczność.**

- Wspomnij osobę z pokoju przez @: zacznij pisać imię w polu wiadomości i wybierz ją z listy. Wzmianka pokazuje jej aktualne imię, a lista wątków może pokazać tylko wątki, w których ktoś Cię wspomniał/a.
- Dzwonek w nagłówku liczy Twoje nieprzeczytane powiadomienia: gdy ktoś Cię wspomni albo przekaże Ci wątek. Kliknięcie otwiera wiadomość, a „Oznacz wszystkie jako przeczytane” zeruje licznik. Nowe powiadomienia przychodzą na żywo.
- Jeśli powiadomienie pozostaje nieprzeczytane przez pięć minut, dostajesz jeden e-mail z podsumowaniem (najwyżej jeden na 15 minut). E-mail podaje, kto, w którym pokoju i w którym wątku, z linkiem, i nigdy nie zawiera samej wiadomości. Wyłączysz go dla każdego rodzaju w „Powiadomienia e-mailem” w menu konta.
- Zareaguj na wiadomość emoji. Każda reakcja pokazuje liczbę i pierwsze osoby; kliknij ją ponownie, aby wycofać swoją.
- Przypinaj wątki i wiadomości w pokoju. Panel przypiętych w nagłówku pokoju pokazuje je wszystkim osobom w pokoju.
- Zobacz, co nowego: kropka na liście pokoi przy pokoju z nową aktywnością, liczba nieprzeczytanych na liście wątków i linia „Nowe” w rozmowie tam, gdzie skończyłeś/aś czytać, z przyciskiem, który do niej przenosi. Gdy przewiniesz w górę, przycisk pokazuje, ile nowych wiadomości czeka poniżej.`,
  },
  {
    version: "0.5.0",
    date: "2026-11-20",
    en: `**Adder 0.5.0: find.**

- Filter a room's threads by status, owner, hashtag and the room's choice and person fields, and sort them by recent activity, newest, number or workflow order. The filter lives in the address bar, so a link shows the same list to whoever opens it.
- Search a room, or all your rooms at once. Results are grouped by thread, the matching words are highlighted, and a click takes you to the message. Words match from their beginning (from three letters) and ignore accents: "awari" finds "awaria" and "awarii", "zolw" finds "Żółw".
- Write #hashtags in messages. Each becomes a link that shows the room's threads that use it.
- Focus on one thread: hide the thread list and read the conversation at full width.
- When the server is busy, the thread list and search ask you to wait a moment and try again.`,
    pl: `**Adder 0.5.0: szukaj.**

- Filtruj wątki pokoju po statusie, właścicielu/właścicielce, hasztagu oraz polach wyboru i osób w pokoju; sortuj je po ostatniej aktywności, od najnowszych, po numerze albo kolejności przepływu pracy. Filtr jest w pasku adresu, więc link pokazuje tę samą listę każdej osobie, która go otworzy.
- Przeszukaj pokój albo wszystkie swoje pokoje naraz. Wyniki pokazują wątki z pasującymi wiadomościami, podświetlają trafione słowa, a kliknięcie przenosi do wiadomości. Słowa pasują od początku (od trzech liter) i bez względu na znaki diakrytyczne: „awari” znajdzie „awaria” i „awarii”, „zolw” znajdzie „Żółw”.
- Pisz #hasztagi w wiadomościach. Każdy staje się linkiem, który pokazuje wątki pokoju z tym hasztagiem.
- Skup się na jednym wątku: ukryj listę wątków i czytaj rozmowę na całej szerokości.
- Gdy serwer jest zajęty, lista wątków i wyszukiwanie proszą o chwilę cierpliwości i ponowną próbę.`,
  },
  {
    version: "0.4.0",
    date: "2026-11-19",
    en: `**Adder 0.4.0: live updates.**

- Rooms update while you watch: new threads and messages, edits and deletions, status, owner and detail changes, members and the room's workflow appear without reloading the page.
- If the connection drops, the header says "Reconnecting…"; once it is back, the page catches up on what it missed.
- If too many of your tabs receive live updates, the header of the extra one says "Live updates off" and it tries again after a minute. A tab that stays hidden for a few minutes stops receiving updates and catches up when you return.
- Someone removed you from a room? It leaves your rooms rail at once.
- Administrators see live-channel figures (open live sockets and whether the updates listener is up) on the telemetry page.`,
    pl: `**Adder 0.4.0: aktualizacje na żywo.**

- Pokoje odświeżają się na Twoich oczach: nowe wątki i wiadomości, edycje i usunięcia, zmiany statusu, właściciela i szczegółów, członkowie oraz przepływ pracy pokoju pojawiają się bez przeładowania strony.
- Gdy połączenie zerwie się, nagłówek pokazuje „Ponowne łączenie…”; po jego powrocie strona nadrabia to, co ją ominęło.
- Jeśli zbyt wiele Twoich kart odbiera aktualizacje na żywo, nagłówek nadmiarowej karty pokazuje „Aktualizacje na żywo wyłączone”, a karta próbuje ponownie po minucie. Karta ukryta przez kilka minut przestaje odbierać aktualizacje i nadrabia zaległości, gdy do niej wrócisz.
- Ktoś usunął/usunęła Cię z pokoju? Pokój od razu znika z Twojej listy.
- Administratorzy/ki widzą na stronie telemetrii wskaźniki kanału na żywo (otwarte gniazda i to, czy nasłuch aktualizacji działa).`,
  },
  {
    version: "0.3.0",
    date: "2026-11-18",
    en: `**Adder 0.3.0: threads and messages.**

- Start a thread in a room: a title, an opening message and the room's metadata fields. Threads are numbered per room (#1, #2, ...), and each has a status and an owner.
- Messages support Markdown and emoji. Authors can edit their own messages; an author, a room owner or an administrator can delete one, which leaves a "removed" note in its place.
- Move a thread through the room's statuses. A closed status freezes the thread: no new messages or edits until it is reopened. The thread's owner, a room owner or an administrator can close, reopen and hand a thread over; any room member can move it between open statuses.
- Each thread keeps a history of what changed and who changed it, in the side panel next to the conversation.
- Removing a status that threads still use now asks where to move those threads.
- The room view has three panes: the rooms, the thread list and the open conversation with its side panel.
- Live updates arrive in the next version: until then the page refreshes when you return to it and after your own actions.`,
    pl: `**Adder 0.3.0: wątki i wiadomości.**

- Rozpocznij wątek w pokoju: tytuł, wiadomość otwierająca i pola metadanych pokoju. Wątki są numerowane w obrębie pokoju (#1, #2, ...), a każdy ma status i właściciela/kę.
- Wiadomości obsługują Markdown i emoji. Autor/ka może edytować własne wiadomości, a autor/ka, właściciel/ka pokoju lub administrator/ka może ją usunąć — w jej miejscu zostaje wtedy adnotacja o usunięciu.
- Przenoś wątek między statusami pokoju. Status zamknięty zamraża wątek: żadnych nowych wiadomości ani zmian, dopóki wątek nie zostanie otwarty ponownie. Właściciel/ka wątku, właściciel/ka pokoju lub administrator/ka może zamknąć, otworzyć ponownie i przekazać wątek, a każda osoba z pokoju może przenosić go między statusami otwartymi.
- Każdy wątek ma historię zmian z informacją, kto ich dokonał, w panelu bocznym obok rozmowy.
- Usuwając status, którego używają jeszcze wątki, wskazujesz teraz, dokąd je przenieść.
- Widok pokoju ma trzy panele: pokoje, listę wątków i otwartą rozmowę z panelem bocznym.
- Aktualizacje na żywo pojawią się w kolejnej wersji: do tego czasu strona odświeża się po powrocie do niej i po Twoich własnych działaniach.`,
  },
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
