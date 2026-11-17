import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import WorkflowTemplateForm from "./WorkflowTemplateForm";
import { ApiError } from "../api/http";
import type { WorkflowTemplateResponse } from "../api/workflowTemplates";
import { renderWithProviders } from "../test/render";
import { blankTemplateSeed, templateSeedOf } from "../utils/workflowTemplateForm";
import type { WorkflowTemplateBody } from "../utils/workflowForm";

const TEMPLATE: WorkflowTemplateResponse = {
  id: 4,
  name: "Incident",
  description: "Major incidents",
  statuses: [
    { key: "open", label: { en: "Open", pl: "Otwarte" }, colour: "RED", category: "OPEN", initial: true },
    { key: "done", label: { en: "Done" }, colour: "TEAL", category: "CLOSED", initial: false },
  ],
  fields: [],
  revision: 3,
  createdAt: 1,
  updatedAt: 2,
};

function Probe() {
  return <p>at {useLocation().pathname}</p>;
}

type Props = { isEdit?: boolean; onSubmit?: (body: WorkflowTemplateBody) => Promise<void>; onReload?: () => Promise<void> };

function renderForm({ isEdit = true, onSubmit = () => Promise.resolve(), onReload }: Props = {}) {
  const seed = isEdit ? templateSeedOf(TEMPLATE) : blankTemplateSeed();
  return renderWithProviders(
    <Routes>
      <Route
        path="/form"
        element={<WorkflowTemplateForm seed={seed} isEdit={isEdit} backTo="/list" onSubmit={onSubmit} onReload={onReload} />}
      />
      <Route path="/list" element={<Probe />} />
    </Routes>,
    { route: "/form" },
  );
}

const save = () => screen.getByRole("button", { name: /^(Save|Create)$/ });
const statusRow = (n: number) => screen.getByRole("group", { name: `Status ${n}` });

afterEach(() => {
  vi.restoreAllMocks();
});

describe("WorkflowTemplateForm", () => {
  test("shows the registry text and the workflow with an English and a Polish column", () => {
    renderForm();
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Incident");
    expect(screen.getByRole("textbox", { name: "Description" })).toHaveValue("Major incidents");
    // The hint describes the workflow region it introduces.
    const region = screen.getByRole("group", { name: "Workflow" });
    expect(region).toHaveAccessibleDescription(/English labels are required/);
    expect(within(statusRow(1)).getByRole("textbox", { name: "Label (EN)" })).toHaveValue("Open");
    expect(within(statusRow(1)).getByRole("textbox", { name: "Label (PL)" })).toHaveValue("Otwarte");
    expect(within(statusRow(2)).getByRole("textbox", { name: "Label (PL)" })).toHaveValue("");
  });

  test("an edit's Save is disabled until something changes and again once it is edited back", async () => {
    const user = userEvent.setup();
    renderForm();
    expect(save()).toBeDisabled();
    const name = screen.getByRole("textbox", { name: "Name" });
    await user.type(name, "!");
    expect(save()).toBeEnabled();
    await user.type(name, "{Backspace}");
    expect(save()).toBeDisabled();
    await user.type(within(statusRow(2)).getByRole("textbox", { name: "Label (PL)" }), "Gotowe");
    expect(save()).toBeEnabled();
  });

  test("a create form's button is enabled while clean, and a blank name stops the save with the field error", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(() => Promise.resolve());
    renderForm({ isEdit: false, onSubmit });
    expect(save()).toBeEnabled();
    await user.click(save());
    expect(await screen.findByText("Name must be 1–100 characters")).toBeInTheDocument();
    expect(screen.getByText("Fix the highlighted fields before saving.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  test("a workflow rule the server would refuse stops the save and marks the row", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(() => Promise.resolve());
    renderForm({ onSubmit });
    await user.clear(within(statusRow(1)).getByRole("textbox", { name: "Label (EN)" }));
    await user.click(save());
    expect(await screen.findByText("The label is required")).toBeInTheDocument();
    expect(screen.getByText("Fix the highlighted fields before saving.")).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
    // The errors then follow the draft: fixing the label clears the row's message.
    await user.type(within(statusRow(1)).getByRole("textbox", { name: "Label (EN)" }), "Open");
    expect(screen.queryByText("The label is required")).not.toBeInTheDocument();
  });

  test("a valid form sends the trimmed body: {en, pl?} labels and a blank description as null", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(() => Promise.resolve());
    renderForm({ isEdit: false, onSubmit });
    await user.type(screen.getByRole("textbox", { name: "Name" }), "  Handover  ");
    await user.type(within(statusRow(1)).getByRole("textbox", { name: "Label (PL)" }), "Otwarte");
    await user.click(save());
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith({
      name: "Handover",
      description: null,
      statuses: [
        { key: "open", label: { en: "Open", pl: "Otwarte" }, colour: "BLUE", category: "OPEN", initial: true },
        { key: "done", label: { en: "Done" }, colour: "TEAL", category: "CLOSED", initial: false },
      ],
      fields: [],
    });
  });

  test("a stale revision keeps the draft, offers Reload and hands the re-seed to the page", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(() => Promise.reject(new ApiError(409, { type: "urn:adder:problem:revision-conflict" })));
    const onReload = vi.fn(() => Promise.resolve());
    renderForm({ onSubmit, onReload });
    await user.type(screen.getByRole("textbox", { name: "Name" }), " v2");
    await user.click(save());
    expect(await screen.findByText("This workflow was changed elsewhere")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Incident v2");
    expect(save()).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Reload the latest version" }));
    expect(onReload).toHaveBeenCalledTimes(1);
    // The next save starts clean of the alert.
    await user.click(save());
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
  });

  test("a Reload after the template was deleted elsewhere says it no longer exists, and keeps the draft", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(() => Promise.reject(new ApiError(409, { type: "urn:adder:problem:revision-conflict" })));
    const onReload = vi.fn(() => Promise.reject(new ApiError(404, {})));
    renderForm({ onSubmit, onReload });
    await user.type(screen.getByRole("textbox", { name: "Name" }), " v2");
    await user.click(save());
    await user.click(await screen.findByRole("button", { name: "Reload the latest version" }));
    expect(await screen.findByText("This workflow template does not exist (or was deleted).")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Incident v2");
  });

  test("a Reload that fails otherwise is status-tagged", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(() => Promise.reject(new ApiError(409, { type: "urn:adder:problem:revision-conflict" })));
    const onReload = vi.fn(() => Promise.reject(new ApiError(500, {})));
    renderForm({ onSubmit, onReload });
    await user.type(screen.getByRole("textbox", { name: "Name" }), " v2");
    await user.click(save());
    await user.click(await screen.findByRole("button", { name: "Reload the latest version" }));
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
  });

  test("any other 409 is the name clash, marked on the name field", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(() => Promise.reject(new ApiError(409, { title: "Conflict" })));
    renderForm({ onSubmit, onReload: () => Promise.resolve() });
    await user.type(screen.getByRole("textbox", { name: "Name" }), "2");
    await user.click(save());
    expect(await screen.findByText("A workflow template with this name already exists")).toBeInTheDocument();
    expect(screen.queryByText("This workflow was changed elsewhere")).not.toBeInTheDocument();
  });

  test("a revision conflict on a create form has no Reload to offer, so it reads as a name clash", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(() => Promise.reject(new ApiError(409, { type: "urn:adder:problem:revision-conflict" })));
    renderForm({ isEdit: false, onSubmit });
    await user.type(screen.getByRole("textbox", { name: "Name" }), "Dup");
    await user.click(save());
    expect(await screen.findByText("A workflow template with this name already exists")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reload the latest version" })).not.toBeInTheDocument();
  });

  test("a failed save names the failure and re-enables the button", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn(() => Promise.reject(new ApiError(403, {})));
    renderForm({ onSubmit });
    await user.type(screen.getByRole("textbox", { name: "Name" }), "2");
    await user.click(save());
    expect(await screen.findByText("Only administrators can edit workflow templates")).toBeInTheDocument();
    expect(save()).toBeEnabled();
  });

  test("Cancel leaves at once while clean", async () => {
    const user = userEvent.setup();
    renderForm();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(await screen.findByText("at /list")).toBeInTheDocument();
  });

  test("Cancel asks first once dirty: Keep editing stays, Discard leaves", async () => {
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByRole("textbox", { name: "Name" }), "!");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    const dialog = await screen.findByRole("dialog", { name: "Discard changes?" });
    await user.click(within(dialog).getByRole("button", { name: "Keep editing" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Incident!");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Discard changes?" })).getByRole("button", { name: "Discard" }));
    expect(await screen.findByText("at /list")).toBeInTheDocument();
  });

  test("the browser's unload prompt is armed only while the form is dirty", async () => {
    const user = userEvent.setup();
    renderForm();
    const unload = () => {
      const event = new Event("beforeunload", { cancelable: true });
      fireEvent(window, event);
      return event.defaultPrevented;
    };
    expect(unload()).toBe(false);
    await user.type(screen.getByRole("textbox", { name: "Name" }), "!");
    expect(unload()).toBe(true);
  });
});
