import type { ReactNode } from "react";
import { Text, Title } from "@mantine/core";
import classes from "./RoomTabHeader.module.css";

/** A room tab's heading below the shared room name and navigation. */
export default function RoomTabHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <header className={classes.header}>
      <div className={classes.intro}>
        <Title order={3} className={classes.title}>{title}</Title>
        {description && <Text size="sm" c="dimmed" maw="66ch">{description}</Text>}
      </div>
      {actions && <div className={classes.actions}>{actions}</div>}
    </header>
  );
}
