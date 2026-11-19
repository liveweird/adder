import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, SegmentedControl, Stack, TextInput } from "@mantine/core";
import { IconSearch } from "@tabler/icons-react";
import { queryProblem } from "../utils/searchParams";
import { SCOPES, type Scope } from "../utils/threadListParams";

/**
 * The search box: a labelled `type="search"` input with the rules of matching under it, the Open / Closed / All control and a submit button. A
 * search is SUBMITTED, not run as you type (it is a heavier read under its own rate limit). The term is checked here first, with the server's
 * rules: a 400 never echoes the term, so this is the only place that can say what is wrong. When the URL's term changes (Back, a shared link,
 * the palette) the box takes it; a term the URL carries that cannot be searched shows its problem at once.
 */
export default function SearchForm({ label, q, scope, onSubmit, onScope }: { label: string; q: string; scope: Scope; onSubmit: (q: string) => void; onScope: (scope: Scope) => void }) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(q);
  // Say what is wrong only once the reader asked (a submit) or the URL already holds a term; an empty box on arrival is not an error.
  const [asked, setAsked] = useState(q !== "");
  // Derived during render (the guarded idiom): the box follows the URL's term, and keeps the reader's focus while it does.
  const [seen, setSeen] = useState(q);
  if (q !== seen) {
    setSeen(q);
    setDraft(q);
    setAsked(q !== "");
  }
  const problem = asked ? queryProblem(draft) : null;

  function submit(event: FormEvent) {
    event.preventDefault();
    setAsked(true);
    if (queryProblem(draft) === null) onSubmit(draft.trim());
  }

  return (
    <form role="search" onSubmit={submit} noValidate>
      <Stack gap="xs">
        <TextInput
          type="search"
          label={label}
          value={draft}
          onChange={(event) => setDraft(event.currentTarget.value)}
          description={t("search.hint")}
          inputWrapperOrder={["label", "input", "error", "description"]}
          error={problem === null ? undefined : t(`search.error.${problem}`)}
          // Announced when it appears (a submit that was refused), not only found by a reader who goes back to the box.
          errorProps={{ role: "alert" }}
          leftSection={<IconSearch size={16} />}
        />
        <Group gap="sm">
          <Button type="submit">{t("search.submit")}</Button>
          <SegmentedControl
            size="xs"
            aria-label={t("search.scope.aria")}
            value={scope}
            onChange={(value) => onScope(value as Scope)}
            data={SCOPES.map((value) => ({ value, label: t(`threads.scope.${value}`) }))}
          />
        </Group>
      </Stack>
    </form>
  );
}
