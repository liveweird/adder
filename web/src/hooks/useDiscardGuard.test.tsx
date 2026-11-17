import { describe, expect, test } from "vitest";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { MantineProvider } from "@mantine/core";
import userEvent from "@testing-library/user-event";
import { render, screen } from "@testing-library/react";
import ConfirmActionModal from "../components/ConfirmActionModal";
import { theme } from "../theme";
import { useDiscardGuard } from "./useDiscardGuard";

function Form({ dirty, title, message }: { dirty: boolean; title?: string; message?: string }) {
  const { requestCancel, confirmProps } = useDiscardGuard({ isDirty: dirty, to: "/list", title, message });
  return (
    <>
      <button type="button" onClick={requestCancel}>
        Cancel form
      </button>
      <ConfirmActionModal {...confirmProps} />
    </>
  );
}

function Where() {
  return <p>at {useLocation().pathname}</p>;
}

function renderForm(props: { dirty: boolean; title?: string; message?: string }) {
  return render(
    <MantineProvider env="test" theme={theme}>
      <MemoryRouter initialEntries={["/edit"]}>
        <Routes>
          <Route path="/edit" element={<Form {...props} />} />
          <Route path="/list" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe("useDiscardGuard", () => {
  test("a clean form leaves at once", async () => {
    renderForm({ dirty: false });
    await userEvent.click(screen.getByRole("button", { name: "Cancel form" }));
    expect(screen.getByText("at /list")).toBeInTheDocument();
  });

  test("a dirty form asks first, and Keep editing stays", async () => {
    const user = userEvent.setup();
    renderForm({ dirty: true });
    await user.click(screen.getByRole("button", { name: "Cancel form" }));
    expect(screen.getByText("Discard changes?")).toBeInTheDocument();
    expect(screen.getByText("You have unsaved changes. If you leave now, they are lost.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Keep editing" }));
    expect(screen.queryByText("Discard changes?")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cancel form" })).toBeInTheDocument();
  });

  test("Discard leaves for the target, and the copy can be overridden", async () => {
    const user = userEvent.setup();
    renderForm({ dirty: true, title: "Throw away?", message: "Really?" });
    await user.click(screen.getByRole("button", { name: "Cancel form" }));
    expect(screen.getByText("Throw away?")).toBeInTheDocument();
    expect(screen.getByText("Really?")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Discard" }));
    expect(screen.getByText("at /list")).toBeInTheDocument();
  });
});
