import emojiData from "@emoji-mart/data";
import { describe, expect, test } from "vitest";

// The server accepts a keycap only in its fully qualified spelling (base, U+FE0F, U+20E3: `threads/Reactions.kt`). The reaction sends the
// picker's `native` string, so every keycap the picker can offer must carry the selector.
type EmojiRecord = { skins: { unified: string; native: string }[] };

describe("the emoji picker's keycaps", () => {
  test("every keycap is spelled with the variation selector", () => {
    const keycaps = Object.values((emojiData as unknown as { emojis: Record<string, EmojiRecord> }).emojis).flatMap((e) =>
      e.skins.filter((s) => s.unified.toLowerCase().endsWith("20e3")),
    );
    expect(keycaps).toHaveLength(12);
    for (const keycap of keycaps) {
      const points = Array.from(keycap.native).map((c) => c.codePointAt(0));
      expect(points[1]).toBe(0xfe0f);
      expect(points[2]).toBe(0x20e3);
      expect(points).toHaveLength(3);
    }
  });
});
