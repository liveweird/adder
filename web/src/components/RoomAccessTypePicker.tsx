import { Group, Radio, Stack, Text, ThemeIcon } from "@mantine/core";
import { IconEye, IconLock, IconWorld } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { RoomAccessType } from "../api/rooms";
import classes from "./RoomAccessTypePicker.module.css";

const TYPES = [
  { value: "PUBLIC", icon: IconWorld },
  { value: "RESTRICTED_VISIBLE", icon: IconEye },
  { value: "RESTRICTED_HIDDEN", icon: IconLock },
] as const;

/** The three creation-time access choices, exposed together so their discovery and joining rules are easy to compare. */
export default function RoomAccessTypePicker({ value, onChange }: { value: RoomAccessType; onChange: (value: RoomAccessType) => void }) {
  const { t } = useTranslation();
  return (
    <fieldset className={classes.fieldset}>
      <legend className={classes.legend}>{t("rooms.access.label")}</legend>
      <Text size="xs" c="dimmed" className={classes.hint}>
        {t("rooms.access.creationHint")}
      </Text>
      <Stack gap="xs">
        {TYPES.map(({ value: option, icon: Icon }) => (
          <label key={option} className={classes.option} data-selected={value === option || undefined}>
            <Radio
              name="accessType"
              value={option}
              checked={value === option}
              onChange={() => onChange(option)}
              aria-describedby={`room-access-${option.toLowerCase()}-description`}
            />
            <Group gap="sm" wrap="nowrap" align="flex-start">
              <ThemeIcon variant="light" color="gray" size="lg" radius="md" aria-hidden="true">
                <Icon size={18} />
              </ThemeIcon>
              <Stack gap={2}>
                <Text size="sm" fw={600}>
                  {t(`rooms.access.${option}.label`)}
                </Text>
                <Text id={`room-access-${option.toLowerCase()}-description`} size="xs" c="dimmed">
                  {t(`rooms.access.${option}.description`)}
                </Text>
              </Stack>
            </Group>
          </label>
        ))}
      </Stack>
    </fieldset>
  );
}
