import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Navigate, useNavigate, useParams } from "react-router-dom";
import { Stack } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { getWorkflowTemplate, updateWorkflowTemplate } from "../api/workflowTemplates";
import { useAdmin } from "../auth";
import EditPageLoadState from "../components/EditPageLoadState";
import PageHeader from "../components/PageHeader";
import WorkflowTemplateForm from "../components/WorkflowTemplateForm";
import { loadErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import { templateSeedOf, type TemplateSeed } from "../utils/workflowTemplateForm";
import { workflowTemplatesPath } from "../utils/workflowTemplateLinks";
import classes from "../components/ManagementSurface.module.css";

/** A seed paired with the revision it was read at (the `X-Expected-Revision` of the save) and a generation that re-keys the form. */
type LoadedSeed = { seed: TemplateSeed; revision: number; generation: number };

/**
 * `/workflow-templates/:id/edit` (ADMIN): the registry text and the two-language workflow of one template, saved as a whole
 * document under the revision the form was seeded with. The seed is taken ONCE from the first read and never follows a
 * background refetch (that would rebase the draft); only Reload on the conflict alert replaces it, on the user's click.
 */
export default function EditWorkflowTemplate() {
  const { id: idParam } = useParams();
  // Keyed by the id: another template's page never inherits this one's seed, draft or revision.
  return <EditTemplatePage key={idParam} id={Number(idParam)} />;
}

function EditTemplatePage({ id }: { id: number }) {
  const { t } = useTranslation();
  const idIsValid = Number.isInteger(id) && id > 0;
  const admin = useAdmin();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [loaded, setLoaded] = useState<LoadedSeed | null>(null);

  // gcTime 0: opening the page always starts from a fresh read, never from a cached copy an earlier visit left behind.
  // No refetch on window focus either: the form is seeded once, so a background read would only be thrown away.
  const detailQuery = { queryKey: ["workflowTemplates", "detail", id], queryFn: () => getWorkflowTemplate(id), gcTime: 0, refetchOnWindowFocus: false };
  const { data, isLoading, error } = useQuery({ ...detailQuery, enabled: idIsValid && admin, retry: false });

  // Derived during render (the guarded idiom): the first read seeds the form, later refetches are ignored.
  if (data && loaded === null) setLoaded({ seed: templateSeedOf(data), revision: data.revision, generation: 1 });

  async function reload() {
    const fresh = await queryClient.fetchQuery({ ...detailQuery, staleTime: 0 });
    setLoaded((previous) => ({ seed: templateSeedOf(fresh), revision: fresh.revision, generation: (previous?.generation ?? 0) + 1 }));
  }

  if (!idIsValid) return <Navigate to={workflowTemplatesPath} replace />;

  if (loaded === null) {
    const notFound = error instanceof ApiError && error.status === 404;
    return (
      <EditPageLoadState
        isLoading={isLoading}
        message={notFound ? t("workflows.templates.notFound") : loadErrorMessage(error, t)}
        backTo={workflowTemplatesPath}
        backLabel={t("workflows.templates.backToList")}
        title={t("workflows.templates.editTitle")}
      />
    );
  }

  return (
    <Stack gap="md" className={classes.page}>
      <PageHeader
        title={t("workflows.templates.editTitle")}
        backTo={{ to: workflowTemplatesPath, label: t("workflows.templates.backToList") }}
      />
      <WorkflowTemplateForm
        key={loaded.generation}
        seed={loaded.seed}
        isEdit
        backTo={workflowTemplatesPath}
        onReload={reload}
        onSubmit={async (body) => {
          await updateWorkflowTemplate(id, body, loaded.revision);
          showSuccessToast(t("workflows.templates.toast.saved"));
          // Everything but the detail refreshes: the detail's result would be thrown away (the next visit reads afresh) and
          // its refetch would hold the Save button until it answered.
          const filters = { queryKey: ["workflowTemplates"], predicate: (query: { queryKey: readonly unknown[] }) => query.queryKey[1] !== "detail" };
          navigate(workflowTemplatesPath);
          await queryClient.cancelQueries(filters);
          await queryClient.invalidateQueries(filters);
        }}
      />
    </Stack>
  );
}
