import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Anchor, Button, Menu, Stack, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconListCheck, IconPencil, IconPlus, IconTrash } from "@tabler/icons-react";
import { useAdmin } from "../auth";
import { deleteWorkflowTemplate, listWorkflowTemplates, type WorkflowTemplateListItem } from "../api/workflowTemplates";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import RegistryListTable from "../components/RegistryListTable";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useRegistryListControls } from "../hooks/useRegistryListControls";
import { browserTimeZone, formatDate } from "../utils/formatDate";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";
import { MAX_TEMPLATE_NAME_LENGTH } from "../utils/workflowTemplateForm";
import { editWorkflowTemplatePath, newWorkflowTemplatePath } from "../utils/workflowTemplateLinks";

const SORT_FIELDS = ["name", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "workflowTemplates";

/**
 * The workflow-template registry (`/workflow-templates`, ADMIN only: the route sits under `RequireAdmin`): the paged list with a
 * name filter, whose rows open the editor page; New template and Delete are the other actions. A room picks a template when it
 * is created (that picker reads the same list for any signed-in user).
 */
export default function WorkflowTemplates() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const admin = useAdmin();
  const { nameFilter, setNameFilter, debouncedName, nameFilterActive, page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    useRegistryListControls<SortField>({ settingsKey: SETTINGS_KEY, sortFields: SORT_FIELDS, initialSortField: "name" });

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["workflowTemplates", "list", page, pageSize, sortParam, debouncedName],
    queryFn: () => listWorkflowTemplates({ page, pageSize, sort: sortParam, name: debouncedName || undefined }),
    placeholderData: keepPreviousData,
    enabled: admin,
  });

  const remove = useDeleteConfirm<WorkflowTemplateListItem>({
    mutationFn: (row) => deleteWorkflowTemplate(row.id),
    onSuccess: () => refreshQueriesAfterMutation(queryClient, ["workflowTemplates"]),
    successMessage: t("workflows.templates.toast.deleted"),
  });

  return (
    <Stack gap="md">
      <PageHeader
        title={t("workflows.templates.title")}
        description={t("workflows.templates.intro")}
        actions={
          <Button component={RouterLink} to={newWorkflowTemplatePath} leftSection={<IconPlus size={16} />}>
            {t("workflows.templates.newTemplate")}
          </Button>
        }
      />

      <FilterPanel activeFilterCount={nameFilterActive ? 1 : 0} storageKey={SETTINGS_KEY}>
        <ClearableTextInput
          label={t("common.field.name")}
          value={nameFilter}
          onChange={setNameFilter}
          clearLabel={t("common.filter.clearName")}
          maxLength={MAX_TEMPLATE_NAME_LENGTH}
        />
      </FilterPanel>

      <RegistryListTable
        label={t("workflows.templates.title")}
        errorTitle={t("workflows.templates.loadFailed")}
        error={error}
        isError={isError}
        isLoading={isLoading}
        hasData={Boolean(data)}
        rowCount={data?.items.length ?? 0}
        columnCount={6}
        emptyIcon={IconListCheck}
        emptyLabel={t("workflows.templates.empty")}
        header={
          <Table.Tr>
            <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("common.field.description")}</Table.Th>
            <Table.Th>{t("workflows.templates.column.statuses")}</Table.Th>
            <Table.Th>{t("workflows.templates.column.fields")}</Table.Th>
            <SortHeader field="updatedAt" label={t("workflows.templates.column.updated")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
          </Table.Tr>
        }
        rows={data?.items.map((template) => (
          <Table.Tr key={template.id}>
            <Table.Td>
              {/* The name is the way into the editor — a real link (the detail-link rule). */}
              <Anchor
                component={RouterLink}
                to={editWorkflowTemplatePath(template.id)}
                fw={500}
                size="sm"
                aria-label={t("workflows.templates.openAria", { name: template.name })}
              >
                {template.name}
              </Anchor>
            </Table.Td>
            <Table.Td>
              <Text size="sm" c={template.description ? undefined : "dimmed"} lineClamp={1}>
                {template.description ?? "—"}
              </Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">{template.statusCount}</Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">{template.fieldCount}</Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">{formatDate(template.updatedAt, "—", browserTimeZone())}</Text>
            </Table.Td>
            <Table.Td style={{ width: 1 }} ta="right">
              <RowActionsMenu label={t("common.table.operationsAria", { name: template.name })}>
                <Menu.Item
                  leftSection={<IconPencil size={14} />}
                  onClick={() => navigate(editWorkflowTemplatePath(template.id))}
                  aria-label={t("common.action.editAria", { name: template.name })}
                >
                  {t("common.action.edit")}
                </Menu.Item>
                <Menu.Divider />
                <Menu.Item
                  color="red"
                  leftSection={<IconTrash size={14} />}
                  onClick={() => remove.requestDelete(template)}
                  aria-label={t("common.action.deleteAria", { name: template.name })}
                >
                  {t("common.action.delete")}
                </Menu.Item>
              </RowActionsMenu>
            </Table.Td>
          </Table.Tr>
        ))}
        total={data?.total ?? 0}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />

      <ConfirmDeleteModal
        confirm={remove}
        title={t("workflows.templates.deleteTitle")}
        errorTitle={t("workflows.templates.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, {
            notFound: "workflows.templates.deleteGone",
            failedStatus: "common.error.actionFailedStatus",
            failed: "common.error.actionFailed",
          })
        }
        body={(template) => t("workflows.templates.deleteBody", { name: template.name })}
      />
    </Stack>
  );
}
