import { useRef, type Ref } from "react";
import { Group, Textarea, type TextareaProps } from "@mantine/core";
import { useMergedRef } from "@mantine/hooks";
import { useTranslation } from "react-i18next";
import { charCountDescription, type CharCountMode } from "../utils/charCount";
import EmojiButton from "./EmojiButton";

type EmojiTextareaProps = Omit<TextareaProps, "value" | "onChange"> & {
  /** Optional to match `form.getInputProps` spreads; treated as "" when absent. */
  value?: string;
  /** Plain-string onChange — `form.getInputProps` accepts raw values, so spreads keep working. */
  onChange: (value: string) => void;
  /** Character-counter visibility when maxLength is set (v2.18.0); "nearLimit" for dense row editors. */
  counter?: CharCountMode | "none";
  /** The textarea element, for a caller that needs the caret (`MentionTextarea`). */
  ref?: Ref<HTMLTextAreaElement>;
  /**
   * The length the limit applies to, when the box shows something other than what is stored (`MentionTextarea` shows `@Name` for a stored
   * `<@id>`). Given, the counter counts it, and the native `maxLength` attribute and the picker's own check, which would count the shown
   * text, stand down: the caller enforces the limit on what it stores.
   */
  measuredLength?: number;
};

// A Mantine Textarea with the shared emoji picker in its rightSection. Picked emoji are
// spliced in at the caret (or appended when the field was never focused); the caret is
// restored after the controlled re-render. Swapping Textarea → EmojiTextarea is prop-
// compatible apart from onChange carrying the string instead of the event.
// With maxLength set it also renders the shared counter (utils/charCount.tsx) below the input (description slot,
// moved under the field the AlertFormFields inputWrapperOrder way); a caller-passed
// description shares that line, hint left / counter right.
export default function EmojiTextarea({
  value,
  onChange,
  maxLength,
  counter = "always",
  description,
  ref: outerRef,
  measuredLength,
  ...rest
}: EmojiTextareaProps) {
  const { t } = useTranslation();
  const own = useRef<HTMLTextAreaElement>(null);
  const ref = useMergedRef(own, outerRef);

  const counterNode =
    maxLength != null && counter !== "none" ? charCountDescription(measuredLength ?? (value ?? "").length, maxLength, counter) : undefined;
  const composedDescription =
    counterNode == null ? (
      description
    ) : description == null ? (
      counterNode
    ) : (
      <Group justify="space-between" gap="xs" wrap="nowrap">
        <span>{description}</span>
        {counterNode}
      </Group>
    );

  const insert = (native: string) => {
    const el = own.current;
    const text = value ?? "";
    const start = el?.selectionStart ?? text.length;
    const end = el?.selectionEnd ?? start;
    const next = text.slice(0, start) + native + text.slice(end);
    // The native maxLength attribute only guards typing, not programmatic sets.
    if (measuredLength === undefined && maxLength != null && next.length > maxLength) return;
    onChange(next);
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(start + native.length, start + native.length);
    });
  };

  return (
    <Textarea
      ref={ref}
      value={value}
      onChange={(e) => onChange(e.currentTarget.value)}
      maxLength={measuredLength === undefined ? maxLength : undefined}
      rightSection={<EmojiButton label={t("common.emoji.insert")} onSelect={insert} />}
      rightSectionWidth={36}
      // Pin the button to the top edge — autosize fields grow and a centered icon would float.
      rightSectionProps={{ style: { alignItems: "flex-start", paddingTop: 4 } }}
      rightSectionPointerEvents="all"
      description={composedDescription}
      {...rest}
    />
  );
}
