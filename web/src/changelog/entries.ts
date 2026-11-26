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
    version: "1.26.0",
    date: "2026-11-26",
    en: `**Adder 1.26.0 — shared session refresh and logout**

- A retry after an interrupted session refresh can succeed on another application instance using the same database.
- Logout revokes the current session's credentials and clears refresh grace together. Other signed-in sessions keep working.`,
    pl: `**Adder 1.26.0 — wspólne odświeżanie i wylogowanie sesji**

- Po przerwanym odświeżeniu sesji ponowna próba może zadziałać na innej instancji aplikacji korzystającej z tej samej bazy danych.
- Wylogowanie unieważnia tokeny bieżącej sesji i jednocześnie kończy okres ponawiania odświeżenia. Inne zalogowane sesje nadal działają.`,
  },
  {
    version: "1.25.0",
    date: "2026-11-26",
    en: `**Adder 1.25.0 — shared live socket limits and presence**

- Your live tab limit is shared by application instances using the same database. Connections release their slots on close, and slots left by a stopped process expire automatically.
- An eligible room member can receive an @here notice while a tab connected to another application instance is in the foreground.`,
    pl: `**Adder 1.25.0 — wspólny limit połączeń i obecność na żywo**

- Instancje aplikacji korzystające z tej samej bazy danych mają wspólny limit Twoich kart z połączeniem na żywo. Zamknięcie połączenia zwalnia miejsce, a miejsca po zatrzymanym procesie wygasają automatycznie.
- Osoba należąca do pokoju może otrzymać powiadomienie @here, gdy jej aktywna karta jest połączona z inną instancją aplikacji.`,
  },
  {
    version: "1.24.0",
    date: "2026-11-26",
    en: `**Adder 1.24.0 — durable live connection tickets**

- A live connection ticket stays available during an app restart until it expires or is used. Adder checks your session again when the connection opens.`,
    pl: `**Adder 1.24.0 — trwałe bilety połączeń na żywo**

- Bilet połączenia na żywo pozostaje dostępny po ponownym uruchomieniu aplikacji, aż wygaśnie lub zostanie wykorzystany. Adder ponownie sprawdza sesję przy otwarciu połączenia.`,
  },
  {
    version: "1.23.0",
    date: "2026-11-26",
    en: `**Adder 1.23.0 — fewer repeat reads after replying**

- When the app and API share an origin, your tab can use one refresh for your reply and its matching live update. Other tabs still show the reply live.
- A live update can still bring the reply into view if its response is lost.`,
    pl: `**Adder 1.23.0 — mniej powtórnych odczytów po odpowiedzi**

- Gdy aplikacja i API mają wspólne źródło, Twoja karta może użyć jednego odświeżenia dla odpowiedzi i powiązanego zdarzenia na żywo. W innych kartach odpowiedź nadal pojawia się na żywo.
- Zdarzenie na żywo nadal może wyświetlić zapisaną wiadomość, jeśli odpowiedź serwera zaginie.`,
  },
  {
    version: "1.22.0",
    date: "2026-11-26",
    en: `**Adder 1.22.0 — faster live account updates**

- Open conversations recheck access after an account role changes, a password changes, or an account is removed. Logout closes live connections using the revoked session credential.
- A separate sign-in keeps its live connection while its own credentials and permissions remain valid.
- After adding and mentioning someone, keyboard focus stays in the reply box so you can continue your draft.`,
    pl: `**Adder 1.22.0 — szybsza reakcja na zmiany konta**

- Otwarte rozmowy ponownie sprawdzają dostęp po zmianie roli lub hasła albo po usunięciu konta. Wylogowanie zamyka połączenia korzystające z unieważnionego tokenu sesji.
- Osobne logowanie zachowuje połączenie, jeśli jego token i uprawnienia są nadal ważne.
- Po dodaniu i oznaczeniu osoby fokus pozostaje w polu odpowiedzi, dzięki czemu możesz dalej pisać.`,
  },
  {
    version: "1.21.0",
    date: "2026-11-26",
    en: `**Adder 1.21.0 — notification retention and email pacing**

- Adder retains up to 5,000 unseen notifications for each person across rooms and delivery channels. Older unseen notifications expire first. Expiration does not mark the conversation read.
- Email digests space notifications from the same sender to the same recipient by at least one hour. Other senders can still reach you at the regular digest interval.
- Reading a busy thread and leaving a room now process notification cleanup in smaller database batches.`,
    pl: `**Adder 1.21.0 — przechowywanie powiadomień i odstępy między e-mailami**

- Adder zachowuje do 5000 niewidzianych powiadomień na osobę, łącznie ze wszystkich pokoi i kanałów. Starsze powiadomienia wygasają w pierwszej kolejności. Wygaśnięcie nie oznacza rozmowy jako przeczytanej.
- Podsumowania e-mail uwzględniają powiadomienia od tej samej osoby do tego samego odbiorcy lub odbiorczyni w odstępach co najmniej godziny. Powiadomienia od innych osób nadal mogą trafiać do podsumowań w zwykłych odstępach.
- Czytanie aktywnego wątku i opuszczanie pokoju porządkuje powiadomienia w mniejszych partiach w bazie danych.`,
  },
  {
    version: "1.20.0",
    date: "2026-11-26",
    en: `**Adder 1.20.0 — thread tags and message edit history**

- Choose curated **Tags** while starting a thread. The tags save with the opening message.
- Open **Edit history** from a message menu to see earlier versions. Authors who are still room members, room owners, and administrators with room access can view them. Deleting a message also deletes its saved versions.
- Open **Organize rooms** from **All rooms**. The sidebar now has more space for your rooms and groups.
- Adder emails you when your password changes, including when an administrator changes it, on servers with email delivery configured.
- The API accepts the explicit \`hashtag\` filter and room hashtag list path. The existing aliases still work. Search and workflow field filters use new database indexes.`,
    pl: `**Adder 1.20.0 — tagi wątków i historia edycji wiadomości**

- Wybierz **Tagi** przy tworzeniu wątku. Zapiszą się razem z pierwszą wiadomością.
- Otwórz **Historię edycji** z menu wiadomości, aby zobaczyć poprzednie wersje. Dostęp mają autorzy i autorki, którzy nadal należą do pokoju, osoby zarządzające pokojem jako właściciele lub właścicielki oraz administratorzy i administratorki z dostępem do pokoju. Usunięcie wiadomości usuwa też zapisane wersje.
- Otwórz **Uporządkuj pokoje** z widoku **Wszystkie pokoje**. Na pasku bocznym jest więcej miejsca na pokoje i grupy.
- Jeśli serwer wysyła pocztę, Adder powiadomi Cię e-mailem o zmianie hasła, także wtedy, gdy zmieni je administrator lub administratorka.
- API obsługuje filtr \`hashtag\` i ścieżkę listy hashtagów w pokoju. Dotychczasowe aliasy nadal działają. Wyszukiwanie i filtry pól obiegu pracy korzystają z nowych indeksów w bazie danych.`,
  },
  {
    version: "1.19.0",
    date: "2026-11-26",
    en: `**Adder 1.19.0 — thread workflow and conversation**

- Use **Thread actions** to delete a thread or merge a duplicate in the same room. The original link opens the duplicate's read-only conversation and points to the destination for new replies. Deleting the destination also deletes its merged conversations.
- Follow status, ownership, and other workflow changes inline between replies. Open **Details** for the full history.
- Choose **Quote in another thread…** to carry a message to another conversation. Readers can view and open the quote while they have access to its source.
- Receive a notice when someone reacts to your message. Team mention notices name the team when you still belong to it.
- Mentions and hashtags in indented code, raw HTML, and image alt text are now ignored.`,
    pl: `**Adder 1.19.0 — obieg pracy i rozmowy w wątkach**

- Użyj menu **Działania wątku**, aby usunąć wątek lub scalić duplikat z innym wątkiem w tym samym pokoju. Dotychczasowy link otwiera rozmowę tylko do odczytu i prowadzi do wątku, w którym można dalej odpowiadać. Usunięcie wątku docelowego usuwa też scalone z nim rozmowy.
- Śledź zmiany statusu, właściciela lub właścicielki i inne zdarzenia obiegu pracy między odpowiedziami. W **Szczegółach** znajdziesz pełną historię.
- Wybierz **Cytuj w innym wątku…**, aby przenieść cytat do innej rozmowy. Osoby z dostępem do wiadomości źródłowej mogą zobaczyć cytat i otworzyć oryginał.
- Otrzymuj powiadomienia o reakcjach na Twoje wiadomości. Powiadomienie o wzmiance podaje nazwę zespołu, jeśli nadal do niego należysz.
- Wzmianki i hashtagi we wciętych blokach kodu, surowym HTML-u i tekście alternatywnym obrazów są pomijane.`,
  },
  {
    version: "1.18.0",
    date: "2026-11-25",
    en: `**Adder 1.18.0 — chat and notification controls**

- Enable browser alerts in **Notification preferences** when your server supports them. Mute a thread to silence future notifications and keep your watch.
- Use **@here** to reach room members with a recently visible Adder tab. Room managers can use **Add and mention** to add someone from the directory while keeping the draft.
- Open the full list of people who reacted to a message or watch a thread. Names in history, search, reactions, and thread lists link to profiles.
- See the previous and new status or owner in the bell. Replying starts a watch, and later replies respect your choice to stop watching.`,
    pl: `**Adder 1.18.0 — rozmowy i ustawienia powiadomień**

- Włącz powiadomienia przeglądarki w **Ustawieniach powiadomień**, jeśli serwer je udostępnia. Wycisz wątek, aby przestać otrzymywać nowe powiadomienia i nadal go obserwować.
- Użyj **@here**, aby powiadomić osoby w pokoju, które niedawno miały widoczną kartę Addera. Osoby zarządzające pokojem mogą użyć opcji **Dodaj i wspomnij**, aby dodać osobę z katalogu i zachować szkic wiadomości.
- Otwórz pełną listę osób, które zareagowały na wiadomość lub obserwują wątek. Nazwy osób w historii, wynikach wyszukiwania, reakcjach i na listach wątków prowadzą do ich profili.
- W dzwonku zobaczysz poprzedni i nowy status oraz zmiany właściciela lub właścicielki. Odpowiedź włącza obserwowanie wątku. Jeśli je wyłączysz, kolejne odpowiedzi uszanują ten wybór.`,
  },
  {
    version: "1.17.0",
    date: "2026-11-25",
    en: `**Adder 1.17.0 — grouped reply notifications**

- The bell groups unseen plain replies in a watched thread. Open the group to start at its earliest reply. Mentions and quotes still get their own entries.
- When you opt in to reply emails, the digest groups replies by thread too.`,
    pl: `**Adder 1.17.0 — grupowanie powiadomień o odpowiedziach**

- Dzwonek grupuje nieprzeczytane odpowiedzi w obserwowanym wątku. Otwórz grupę, aby zacząć od najstarszej odpowiedzi. Wzmianki i cytaty nadal mają własne pozycje.
- Jeśli włączysz powiadomienia o odpowiedziach e-mailem, podsumowanie też pogrupuje je według wątków.`,
  },
  {
    version: "1.16.0",
    date: "2026-11-25",
    en: `**Adder 1.16.0 — search unread threads**

- Select **Unread** in room or global search to find threads with new messages for you. Use it on its own or combine it with keywords, status, hashtags and other filters.`,
    pl: `**Adder 1.16.0 — wyszukiwanie nieprzeczytanych wątków**

- Wybierz **Nieprzeczytane** w wyszukiwarce pokoju lub globalnej, aby znaleźć wątki z nowymi wiadomościami. Użyj tego filtra samodzielnie lub połącz go ze słowami kluczowymi, statusem, hashtagami i innymi filtrami.`,
  },
  {
    version: "1.15.0",
    date: "2026-11-25",
    en: `**Adder 1.15.0 — return to a quoting reply**

- After opening a quoted message, select **Back to reply** to return to the reply that contained it. Adder scrolls to the reply and focuses it.
- **Back to latest** remains available when you want to resume at the end of the thread.`,
    pl: `**Adder 1.15.0 — powrót do odpowiedzi z cytatem**

- Po otwarciu cytowanej wiadomości wybierz **Wróć do odpowiedzi**, aby wrócić do odpowiedzi z cytatem. Adder przewinie rozmowę do tej odpowiedzi i przeniesie na nią fokus.
- Wybierz **Wróć do najnowszych**, aby przejść na koniec wątku.`,
  },
  {
    version: "1.14.0",
    date: "2026-11-25",
    en: `**Adder 1.14.0 — room membership notifications**

- The bell tells you when someone adds you to a room or approves your join request. Select **Open room** to go there, including for hidden rooms.
- Choose in-app and email delivery for these notices under **Notification preferences**. In-app is on by default and email is opt-in.`,
    pl: `**Adder 1.14.0 — powiadomienia o członkostwie w pokojach**

- Dzwonek powiadomi Cię, gdy ktoś doda Cię do pokoju lub zaakceptuje Twoją prośbę o dołączenie. Wybierz **Otwórz pokój**, aby przejść do pokoju, także ukrytego.
- W **Ustawieniach powiadomień** wybierz, czy chcesz otrzymywać je w aplikacji i pocztą elektroniczną. Powiadomienia w aplikacji są domyślnie włączone, a e-mail możesz włączyć samodzielnie.`,
  },
  {
    version: "1.13.0",
    date: "2026-11-25",
    en: `**Adder 1.13.0 — external links and previews**

- Review the destination before opening an external message link or Markdown image. Select **Open link** to open it in a new tab.
- Select **Preview** to see a page title or a supported image in a compact popover. Already loaded previews can appear on hover in the conversation.`,
    pl: `**Adder 1.13.0 — linki zewnętrzne i podglądy**

- Sprawdź adres przed otwarciem linku zewnętrznego lub obrazu Markdown w wiadomości. Wybierz **Otwórz link**, aby przejść do nowej karty.
- Wybierz **Podgląd**, aby zobaczyć tytuł strony lub obsługiwany obraz w małym oknie. Wczytany podgląd możesz ponownie wyświetlić, najeżdżając na link w rozmowie.`,
  },
  {
    version: "1.12.0",
    date: "2026-11-25",
    en: `**Adder 1.12.0 — room organizer**

- Use **Organize rooms** to drag rooms within or between groups and to reorder groups. Collapse groups to make long lists easier to browse.
- Use the row menus to move rooms and groups by keyboard or tap. Save to apply the new sidebar layout across devices.`,
    pl: `**Adder 1.12.0 — organizowanie pokoi**

- Otwórz **Organizuj pokoje**, aby przeciągać pokoje w obrębie grup i między nimi oraz zmieniać kolejność grup. Zwijaj grupy, aby ułatwić przeglądanie długich list.
- Użyj menu przy pokoju lub grupie, aby zmienić położenie klawiaturą lub dotykiem. Zapisz układ paska bocznego, aby korzystać z niego na innych urządzeniach.`,
  },
  {
    version: "1.11.0",
    date: "2026-11-25",
    en: `**Adder 1.11.0 — search by status and hashtag**

- Use **status:investigating** to filter by a thread's current workflow status and **hashtag:incident** to find messages tagged #incident.
- Combine the new filters in global, room or in-thread search. In global search, select a room to get suggestions from its workflow and hashtags.`,
    pl: `**Adder 1.11.0 — wyszukiwanie według statusu i hashtagu**

- Użyj **status:investigating**, aby filtrować wątki według bieżącego statusu, oraz **hashtag:incident**, aby znaleźć wiadomości oznaczone #incident.
- Łącz nowe filtry podczas wyszukiwania globalnego, w pokoju lub w wątku. W wyszukiwaniu globalnym wybierz pokój, aby zobaczyć podpowiedzi jego statusów i hashtagów.`,
  },
  {
    version: "1.10.0",
    date: "2026-11-25",
    en: `**Adder 1.10.0 — find in thread**

- Use **Find in thread** to search the current conversation with keywords or structured clauses. Results show the author, time and a highlighted excerpt.
- Open a result, quote, pin or message link to see it in context. Browse earlier or later messages, then choose **Back to latest**. Reading an older section leaves newer messages unread.`,
    pl: `**Adder 1.10.0 — wyszukiwanie w wątku**

- Użyj opcji **Szukaj w wątku**, aby wyszukać słowa kluczowe lub użyć operatorów w bieżącej rozmowie. Wyniki pokazują autora/autorkę, czas i wyróżniony fragment.
- Otwórz wynik, cytat, przypięcie lub link do wiadomości, aby zobaczyć jej kontekst. Przejdź do starszych lub nowszych wiadomości. Opcja **Wróć do najnowszych** prowadzi na koniec rozmowy. Przeglądanie historii pozostawia nowsze wiadomości jako nieprzeczytane.`,
  },
  {
    version: "1.9.0",
    date: "2026-11-24",
    en: `**Adder 1.9.0 — structured search**

- Search for an exact phrase, exclude words, or match a word prefix. Combine clauses to narrow results by room, author, participant, owner, date, pins, links, reactions, or emoji.
- Use the search syntax guide to insert clauses. Suggestions complete room and person filters and offer corrections for misspelled filter names.`,
    pl: `**Adder 1.9.0 — wyszukiwanie z operatorami**

- Wyszukuj dokładne frazy, wykluczaj słowa lub dopasowuj ich początki. Łącz warunki, aby zawęzić wyniki według pokoju, autora/autorki, uczestnika/uczestniczki, właściciela/właścicielki wątku, daty, przypięć, linków, reakcji lub emoji.
- Otwórz przewodnik po składni, aby wstawiać warunki. Podpowiedzi uzupełniają nazwy pokojów i osób oraz pomagają poprawić literówki w nazwach operatorów.`,
  },
  {
    version: "1.8.0",
    date: "2026-11-24",
    en: `**Adder 1.8.0 — personal room groups**

- Open **Organize rooms** to create groups for the rooms you belong to. Name them and move rooms between them.
- Reorder groups and rooms, then save to apply the order in your sidebar. The layout is private and follows your account across devices.`,
    pl: `**Adder 1.8.0 — osobiste grupy pokojów**

- Otwórz **Uporządkuj pokoje**, aby utworzyć grupy pokojów, do których należysz. Nadaj grupom nazwy i przenoś pokoje między nimi.
- Zmień kolejność grup i pokojów, a następnie zapisz układ, aby pojawił się na pasku bocznym. Układ jest prywatny i dostępny po zalogowaniu na innych urządzeniach.`,
  },
  {
    version: "1.7.0",
    date: "2026-11-23",
    en: `**Adder 1.7.0 — personal Home**

- Jump from Home to the latest replies quoting your messages. Unread replies are marked and recent quotes stay visible after you read them.
- Pick up open threads you own and see what changed in threads you watch. Watched threads with unread replies appear first.
- See the rooms you own and open pending join requests from Home.`,
    pl: `**Adder 1.7.0 — osobista strona główna**

- Ze strony głównej przejdź bezpośrednio do najnowszych odpowiedzi cytujących Twoje wiadomości. Nieprzeczytane odpowiedzi są oznaczone, a ostatnie cytaty pozostają widoczne po przeczytaniu.
- Wróć do otwartych wątków, którymi zarządzasz, i sprawdź zmiany w obserwowanych wątkach. Wątki z nieprzeczytanymi odpowiedziami pojawiają się najpierw.
- Zobacz pokoje, którymi zarządzasz, i otwórz oczekujące prośby o dołączenie bezpośrednio ze strony głównej.`,
  },
  {
    version: "1.6.0",
    date: "2026-11-23",
    en: `**Adder 1.6.0 — room access**

- Choose a room's access when you create it. Anyone signed in can join a public room or request access to a restricted visible room. Only members can find a restricted hidden room.
- Room owners can approve or decline requests from the Members tab and add people directly. New members can read the room's existing conversations.
- Owners can promote other members to co-owners and step down while another active owner remains. Existing rooms become restricted hidden.`,
    pl: `**Adder 1.6.0 — dostęp do pokoi**

- Wybierz dostęp podczas tworzenia pokoju. Każda zalogowana osoba może dołączyć do pokoju publicznego lub poprosić o dostęp do ograniczonego pokoju widocznego. Ograniczony pokój ukryty widzą tylko jego członkowie i członkinie.
- Właściciele/ki mogą zatwierdzać i odrzucać prośby na karcie „Członkowie” oraz dodawać osoby bezpośrednio. Nowe osoby w pokoju mogą czytać wcześniejsze rozmowy.
- Właściciele/ki mogą nadawać uprawnienia innym osobom i zrezygnować z roli, jeśli w pokoju pozostaje inny aktywny właściciel lub właścicielka. Dotychczasowe pokoje stają się ukryte.`,
  },
  {
    version: "1.5.0",
    date: "2026-11-23",
    en: `**Adder 1.5.0 — clearer room tabs**

- Search puts Keywords and Search together, groups the filters, and makes matching threads and messages easier to scan.
- Members starts with the roster and puts Add people within reach of room managers. Member cards fit narrow screens.
- Workflow distinguishes statuses from custom fields, aligns the editor controls, and keeps Save and Cancel in reach as you scroll.
- Settings gives room details, archive and unarchive, and deletion separate sections. The tab navigation marks the current page and wraps when it needs more space.`,
    pl: `**Adder 1.5.0 — czytelniejsze karty pokoju**

- W wyszukiwarce znajdziesz przycisk Szukaj obok słów kluczowych, pogrupowane filtry i czytelniejszą listę pasujących wątków oraz wiadomości.
- Na karcie członków/członkiń najpierw zobaczysz listę osób. Jeśli zarządzasz pokojem, masz pod ręką przycisk Dodaj osoby. Na wąskim ekranie lista zmienia się w karty.
- W edytorze przepływu pracy statusy i dodatkowe pola mają osobne sekcje, a przyciski Zapisz i Anuluj pozostają pod ręką podczas przewijania.
- W ustawieniach dane, archiwizacja i usuwanie pokoju mają osobne sekcje. Nawigacja wskazuje bieżącą kartę i w razie potrzeby przenosi pozostałe do kolejnego wiersza.`,
  },
  {
    version: "1.4.1",
    date: "2026-11-22",
    en: `**Adder 1.4.1 — thread status and refreshed logo**

- Change a thread’s status directly from its header. You see only the moves available to you, and the app asks for confirmation before closing the thread.
- The sign-in screens and app header now show a front-facing Strike adder in light and dark themes.
- Browser tabs use a simplified version of the same mark at small sizes.`,
    pl: `**Adder 1.4.1 — status wątku i odświeżone logo**

- Zmieniaj status wątku bezpośrednio w jego nagłówku. Zobaczysz tylko dostępne dla Ciebie akcje, a przed zamknięciem wątku aplikacja poprosi o potwierdzenie.
- Na ekranach logowania i w nagłówku aplikacji zobaczysz znak Strike ze żmiją widzianą od przodu w jasnym i ciemnym motywie.
- Na kartach przeglądarki zobaczysz uproszczoną wersję tego samego znaku w małym rozmiarze.`,
  },
  {
    version: "1.4.0",
    date: "2026-11-22",
    en: `**Adder 1.4.0: a clearer workspace.**

- Chat now uses the available screen more effectively. On larger screens the thread list and conversation scroll independently, while phones give an open thread a focused view with a clear “Back to threads” action.
- Compact room and thread headers keep the title, status, owner and essential actions close without crowding the conversation.
- Messages have a clearer author-and-time hierarchy, comfortable line lengths, quieter actions and more legible quote and pin treatments.
- The reply composer stays pinned below the message timeline, remains compact and reachable, keeps Send prominent and preserves its character count and help for assistive technology.
- Thread details hold workflow controls, metadata, tags, timestamps and history in a structured panel that does not compete with the discussion.
- Home now shows up to six recently active rooms you have joined, with unread cues that make it easier to return to current work.
- All rooms is a scannable table on larger screens and responsive cards on narrow screens, with sorting and filters available in both layouts.
- The application header, mobile navigation and command palette are cleaner and more consistent. The phone drawer supports keyboard focus, Escape and backdrop closing.`,
    pl: `**Adder 1.4.0: czytelniejsza przestrzeń pracy.**

- Czat lepiej wykorzystuje dostępną przestrzeń. Na większych ekranach lista wątków i rozmowa przewijają się niezależnie, a na telefonie otwarty wątek dostaje skupiony widok z wyraźną akcją „Wróć do wątków”.
- Zwarte nagłówki pokoju i wątku trzymają tytuł, status, właściciela/kę i najważniejsze akcje blisko, bez przytłaczania rozmowy.
- Wiadomości mają czytelniejszą hierarchię autora/ki i czasu, wygodną szerokość tekstu, spokojniejsze akcje oraz bardziej czytelne cytaty i przypięcia.
- Pole odpowiedzi pozostaje przypięte pod listą wiadomości, zwarte i łatwo dostępne, wyróżnia przycisk wysyłania oraz zachowuje licznik znaków i pomoc dla technologii asystujących.
- Szczegóły wątku mieszczą ustawienia przepływu pracy, metadane, tagi, znaczniki czasu i historię w uporządkowanym panelu, który nie konkuruje z rozmową.
- Strona główna pokazuje do sześciu ostatnio aktywnych pokoi, do których należysz, wraz z oznaczeniami nieprzeczytanych treści.
- „Wszystkie pokoje” to czytelna tabela na większych ekranach i responsywne karty na wąskich, z sortowaniem i filtrami dostępnymi w obu układach.
- Nagłówek aplikacji, nawigacja mobilna i paleta poleceń są prostsze i bardziej spójne. Panel na telefonie obsługuje fokus klawiatury oraz zamykanie klawiszem Escape i kliknięciem tła.`,
  },
  {
    version: "1.3.0",
    date: "2026-11-22",
    en: `**Adder 1.3.0: quote a message.**

- Reply to a particular message by quoting it: press the quote button beside a message, or choose "Quote" in its menu. A "Quoting …" line appears above the Reply box; remove it with its × if you change your mind.
- Your reply shows the quoted message above your text, with its author. Click the quote to jump to the original message in the thread.
- A quote always shows the message as it reads now: it says "(edited)" when the original was changed afterwards, and "The quoted message was deleted." when it was removed, without its text.
- The author of the quoted message gets a notification ("quoted your message"), unless you quote yourself. Switch it off or on under Notification preferences, Mentions ("Someone quotes my message").
- You can quote any earlier message of the same thread, including the opening message and your own.
- For administrators: if you ever roll back below 1.3.0, ask people to reload their open Adder tabs.`,
    pl: `**Adder 1.3.0: cytowanie wiadomości.**

- Odpowiedz na konkretną wiadomość, cytując ją: naciśnij przycisk cytatu obok wiadomości albo wybierz „Cytuj” w jej menu. Nad polem „Odpowiedź” pojawi się wiersz „Cytujesz: …”; jeśli zmienisz zdanie, usuń go przyciskiem ×.
- Twoja odpowiedź pokazuje cytowaną wiadomość nad Twoim tekstem, razem z jej autorem/ką. Kliknij cytat, aby przejść do oryginalnej wiadomości w wątku.
- Cytat zawsze pokazuje wiadomość w jej obecnej postaci: mówi „(edytowana)”, gdy oryginał został później zmieniony, i „Cytowana wiadomość została usunięta.”, gdy go usunięto, bez jego treści.
- Autor/ka cytowanej wiadomości dostaje powiadomienie („cytuje Twoją wiadomość”), chyba że cytujesz siebie. Włączysz je lub wyłączysz w Ustawieniach powiadomień, w grupie Wzmianki („Ktoś cytuje moją wiadomość”).
- Możesz zacytować każdą wcześniejszą wiadomość tego samego wątku, także wiadomość otwierającą i własną.
- Dla administratorów/rek: jeśli kiedyś przywrócisz wersję starszą niż 1.3.0, poproś o ponowne załadowanie otwartych kart Addera.`,
  },
  {
    version: "1.2.0",
    date: "2026-11-22",
    en: `**Adder 1.2.0: a roomier room view, tags and unread.**

- The side panel with a thread's details now opens on demand: press "Details" to show it. It stays the way you left it, for every room and thread, and the room headers are more compact, so the conversation and the message box get the space.
- Pinned things are marked where they stand: a pinned message says who pinned it, and a pinned thread carries a "Pinned" badge. An open thread shows its pinned messages in a strip under its header, where you can jump to one or unpin it.
- Click a name to open a person's profile page: their teams and the rooms you share with them (administrators see all of the person's rooms). Roles and settings are never shown to others.
- People, rooms and teams are links wherever they are named: a message's author, a mention, a thread's owner, the history, notifications, search results, and the member and team tables. An account that was removed opens a "not available" page.
- A thread's hashtags now show on its row in the list and in its header, and \`#42\` in a message is a link to thread 42 of the same room.
- Tags: administrators curate the list of tags under Administration, Tags (the categories Kind and Area come ready to use). Room members add tags to a thread in its details; on a closed thread only its owner, a room owner or an administrator can. A tag an administrator has retired shows struck through until the next time the tags are saved.
- Search by tag: filter the thread list, the search in a room and the search across rooms by one or more tags. A tag alone is enough, no words needed.
- "Unread" on the thread list shows only the threads with something new for you. "Mark all as read" in a room clears all of its threads, and your notifications from it, for you only, after you confirm.
- The browser tab's title shows how many unseen notifications you have, for example "(3) Incident 42 · Adder", the same number as the bell.
- A tab you are not looking at goes quiet: after 5 minutes it stops following rooms but still counts your notifications, and after 8 hours it disconnects and drops the number. Notifications you read in one tab are cleared in your other tabs of the same browser.`,
    pl: `**Adder 1.2.0: przestronniejszy widok pokoju, tagi i nieprzeczytane.**

- Panel boczny ze szczegółami wątku otwiera się teraz na życzenie: naciśnij „Szczegóły”, aby go pokazać. Zostaje tak, jak go zostawiasz, dla wszystkich pokoi i wątków, a nagłówki pokoi są bardziej zwarte, więc rozmowa i pole wiadomości dostają więcej miejsca.
- Przypięte elementy są oznaczone tam, gdzie się znajdują: przypięta wiadomość mówi, kto ją przypiął, a przypięty wątek ma plakietkę „Przypięty”. Otwarty wątek pokazuje swoje przypięte wiadomości w pasku pod nagłówkiem, skąd możesz do którejś przejść albo ją odpiąć.
- Kliknij imię i nazwisko, aby otworzyć stronę osoby: jej zespoły i pokoje, które macie wspólne (administratorzy/rki widzą wszystkie pokoje tej osoby). Role i ustawienia nigdy nie są pokazywane innym.
- Osoby, pokoje i zespoły są linkami wszędzie, gdzie padają ich nazwy: autor wiadomości, wzmianka, właściciel/ka wątku, historia, powiadomienia, wyniki wyszukiwania oraz tabele członków i zespołów. Usunięte konto otwiera stronę „niedostępne”.
- Hasztagi wątku widać teraz w jego wierszu na liście i w nagłówku, a \`#42\` w wiadomości jest linkiem do wątku 42 w tym samym pokoju.
- Tagi: administratorzy/rki prowadzą listę tagów w sekcji Administracja, Tagi (kategorie Kind i Area są gotowe do użycia). Członkowie i członkinie pokoju dodają tagi do wątku w jego szczegółach; w zamkniętym wątku mogą to zrobić tylko jego właściciel/ka, właściciel/ka pokoju lub administrator/ka. Tag wycofany przez administratora/rkę jest przekreślony do następnego zapisu tagów.
- Wyszukiwanie po tagu: filtruj listę wątków, wyszukiwanie w pokoju i wyszukiwanie we wszystkich pokojach według jednego lub kilku tagów. Sam tag wystarczy, nie trzeba wpisywać słów.
- „Nieprzeczytane” na liście wątków pokazuje tylko wątki, w których jest coś nowego dla Ciebie. „Oznacz wszystko jako przeczytane” w pokoju oznacza wszystkie jego wątki i Twoje powiadomienia z tego pokoju, tylko dla Ciebie, po potwierdzeniu.
- Tytuł karty przeglądarki pokazuje, ile masz nieprzeczytanych powiadomień, na przykład „(3) Incident 42 · Adder”: to ta sama liczba, co przy dzwonku.
- Karta, na którą nie patrzysz, przycicha: po 5 minutach przestaje śledzić pokoje, ale nadal liczy powiadomienia, a po 8 godzinach rozłącza się i usuwa liczbę z tytułu. Powiadomienia przeczytane na jednej karcie znikają też na pozostałych kartach tej samej przeglądarki.`,
  },
  {
    version: "1.1.0",
    date: "2026-11-21",
    en: `**Adder 1.1.0: watching, group mentions and notification choices.**

- **Email notifications are now off for every kind, including mentions and hand-overs.** If you got the email digest before, switch it back on: open "Notification preferences" in the account menu and tick "Email" for the kinds you want. Emails for your account itself, such as password reset links and sign-in codes, are not affected and are always sent.
- Watch a thread to follow it: press "Watch this thread" in a thread. You watch the threads you start and the ones handed over to you, and you can stop at any time. "Watching" on the thread list, and "Threads I watch" in the menu and in search, show only those threads.
- New notifications: a new thread in a room you are in, a reply in a thread you watch, and a watched thread that moves to another status, is closed or reopened, or changes owner. Each person gets one notification per event, with the most specific reason.
- Mention a whole group: \`@\` and a team's handle (for example \`@night-shift\`) for the people on a team, \`@watchers\` for those who watch the thread, and \`@everyone\` for the room. An administrator sets a team's mention handle in the team editor. Anyone in the room can use them. The composer shows how many people \`@everyone\` will notify.
- "Notification preferences" is now a grid: for each of the eleven kinds, choose whether it shows in the app (on by default) and whether it is also sent by email (off by default).`,
    pl: `**Adder 1.1.0: obserwowanie, wzmianki grupowe i wybór powiadomień.**

- **Powiadomienia e-mailem są teraz domyślnie wyłączone dla każdego rodzaju, także dla wzmianek i przekazania wątku.** Jeśli dotąd dostawałeś/aś e-mail z podsumowaniem, włącz go ponownie: otwórz „Ustawienia powiadomień” w menu konta i zaznacz „E-mail” przy rodzajach, które chcesz dostawać. Wiadomości dotyczące samego konta, takie jak linki do resetu hasła i kody logowania, nie zmieniają się i zawsze są wysyłane.
- Obserwuj wątek, aby go śledzić: naciśnij „Obserwuj ten wątek” w wątku. Obserwujesz wątki, które zakładasz, i te, które ktoś Ci przekazał, i w każdej chwili możesz przestać. „Obserwowane” na liście wątków oraz „Wątki, które obserwuję” w menu i w wyszukiwaniu pokazują tylko te wątki.
- Nowe powiadomienia: nowy wątek w pokoju, w którym jesteś, odpowiedź w obserwowanym wątku oraz obserwowany wątek, który zmienia status, zostaje zamknięty lub otwarty ponownie albo zmienia właściciela/kę. Każda osoba dostaje jedno powiadomienie o zdarzeniu, z najbardziej szczegółowym powodem.
- Wspomnij całą grupę: \`@\` i uchwyt zespołu (na przykład \`@night-shift\`) dla osób z zespołu, \`@watchers\` dla osób obserwujących wątek i \`@everyone\` dla całego pokoju. Uchwyt do wzmianek zespołu ustawia administrator/ka w edytorze zespołu. Mogą ich używać wszyscy w pokoju. Pole wiadomości pokazuje, ile osób powiadomi \`@everyone\`.
- „Ustawienia powiadomień” to teraz siatka: dla każdego z jedenastu rodzajów wybierasz, czy pokazuje się w aplikacji (domyślnie tak) i czy jest też wysyłany e-mailem (domyślnie nie).`,
  },
  {
    version: "1.0.0",
    date: "2026-11-21",
    en: `**Adder 1.0.0: the checkup.**

- Every browser tab names its page ("Members · Night Shift · Adder"), and the room, member and template lists show dates in your own time zone.
- Unread and unseen things share one blue cue: the dot and counts in the rooms rail and the thread list, the "New" line in the conversation, the bell and "What's new". Other counters are gray.
- Press Ctrl+Enter (Cmd+Enter on a Mac) to start a new thread from its form.
- Thread history no longer keeps the old and new values of text and link fields or of the title, so a pasted secret does not stay there after you edit it away. It still says what changed and who changed it.
- Emailed sign-in codes are harder to guess: after repeated wrong codes the account is locked for longer each time, from 15 minutes up to a day. Resetting your password by email lifts the lock.
- If you change a room too fast (its name, settings, members or workflow, or archive it), Adder asks you to wait a few seconds instead of reporting an error.
- One person can cause notification emails to at most 20 other people a day; everyone still sees the bell.
- For integrations: the API's names are consistent before the 1.0 freeze. A person is \`<role>Id\` (\`managerId\`, \`ownerId\`, \`actorId\`, \`mentionedId\`), a yes/no flag is a plain word (\`seen\`), and free-text filters are \`q\`.
- For administrators: production refuses to start with the default database password, backup and restore scripts are tested, and the Kubernetes manifests serve the app through an Ingress.`,
    pl: `**Adder 1.0.0: przegląd.**

- Każda karta przeglądarki pokazuje nazwę strony („Członkowie · Night Shift · Adder”), a listy pokoi, członków i szablonów pokazują daty w Twojej strefie czasowej.
- Nieprzeczytane i nowe rzeczy mają jeden niebieski znak: kropkę i liczniki na liście pokoi i liście wątków, linię „Nowe” w rozmowie, dzwonek i „Co nowego”. Pozostałe liczniki są szare.
- Naciśnij Ctrl+Enter (Cmd+Enter na Macu), aby założyć nowy wątek z formularza.
- Historia wątku nie przechowuje już starych i nowych wartości pól tekstowych i linków ani tytułu, więc wklejony sekret nie zostaje w niej po usunięciu. Nadal widać, co się zmieniło i kto to zmienił/a.
- Kody logowania z e-maila trudniej odgadnąć: po kolejnych błędnych kodach konto blokuje się za każdym razem na dłużej, od 15 minut do doby. Reset hasła przez e-mail zdejmuje blokadę.
- Gdy zmieniasz pokój zbyt szybko (nazwę, ustawienia, członków, przepływ pracy albo archiwizację), Adder prosi o kilka sekund przerwy zamiast zgłaszać błąd.
- Jedna osoba może wywołać e-maile z powiadomieniami do najwyżej 20 innych osób dziennie; dzwonek widzą wszyscy.
- Dla integracji: nazwy w API są spójne przed zamrożeniem wersji 1.0. Osoba to \`<rola>Id\` (\`managerId\`, \`ownerId\`, \`actorId\`, \`mentionedId\`), flaga tak/nie to proste słowo (\`seen\`), a filtry tekstowe to \`q\`.
- Dla administratorów/ek: wersja produkcyjna nie uruchomi się z domyślnym hasłem bazy danych, skrypty kopii zapasowej i odtwarzania są przetestowane, a manifesty Kubernetes udostępniają aplikację przez Ingress.`,
  },
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
