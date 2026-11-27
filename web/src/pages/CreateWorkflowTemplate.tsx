import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { Stack } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { createWorkflowTemplate } from "../api/workflowTemplates";
import PageHeader from "../components/PageHeader";
import WorkflowTemplateForm from "../components/WorkflowTemplateForm";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import { blankTemplateSeed } from "../utils/workflowTemplateForm";
import { workflowTemplatesPath } from "../utils/workflowTemplateLinks";
import classes from "../components/ManagementSurface.module.css";

/** `/workflow-templates/new` (ADMIN): a name, a description and a workflow, starting from a valid two-status skeleton. */
export default function CreateWorkflowTemplate() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  // Seeded once: the form's baseline must not change under a re-render.
  const [seed] = useState(blankTemplateSeed);

  return (
    <Stack gap="md" className={classes.page}>
      <PageHeader
        title={t("workflows.templates.createTitle")}
        backTo={{ to: workflowTemplatesPath, label: t("workflows.templates.backToList") }}
      />
      <WorkflowTemplateForm
        seed={seed}
        isEdit={false}
        backTo={workflowTemplatesPath}
        onSubmit={async (body) => {
          await createWorkflowTemplate(body);
          showSuccessToast(t("workflows.templates.toast.created"));
          await refreshQueriesAfterMutation(queryClient, ["workflowTemplates"]);
          navigate(workflowTemplatesPath);
        }}
      />
    </Stack>
  );
}
