import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { loadErrorMessage, saveErrorMessage } from "./saveError";

const t = i18n.t;

const KEYS = {
  forbidden: "users.lastAdminError",
  notFound: "users.userGone",
  conflict: "users.emailAlreadyInUse",
  invalid: "common.error.actionFailed",
  failedStatus: "common.error.createFailedStatus",
  failed: "common.error.createFailedNetwork",
} as const;

const timeout = new DOMException("timed out", "TimeoutError");

describe("saveErrorMessage", () => {
  test("maps each status to its key and falls back per class", () => {
    expect(saveErrorMessage(new ApiError(403, null), t, KEYS)).toBe(t(KEYS.forbidden));
    expect(saveErrorMessage(new ApiError(404, null), t, KEYS)).toBe(t(KEYS.notFound));
    expect(saveErrorMessage(new ApiError(409, null), t, KEYS)).toBe(t(KEYS.conflict));
    expect(saveErrorMessage(new ApiError(400, null), t, KEYS)).toBe(t(KEYS.invalid));
    expect(saveErrorMessage(new ApiError(500, null), t, KEYS)).toBe("Create failed (500)");
    expect(saveErrorMessage(timeout, t, KEYS)).toBe(t("common.error.timeout"));
    expect(saveErrorMessage(new Error("net"), t, KEYS)).toBe(t(KEYS.failed));
  });

  test("a room-busy 429 has its own words even where the page maps 429 to its bucket copy", () => {
    const busy = new ApiError(429, { type: "urn:adder:problem:room-busy" });
    const bucket = { ...KEYS, tooManyRequests: "threads.message.error.tooManyRequests" } as const;
    expect(saveErrorMessage(busy, t, bucket)).toBe("The room is busy right now — try again in a moment.");
    expect(saveErrorMessage(busy, t, KEYS)).toBe("The room is busy right now — try again in a moment.");
    expect(saveErrorMessage(new ApiError(429, null), t, bucket)).toBe(t("threads.message.error.tooManyRequests"));
  });

  describe("retryAfter: the shared too-fast words", () => {
    const keys = { ...KEYS, tooManyRequests: "threads.message.error.tooManyRequests", retryAfter: true } as const;
    const tooFast = (retryAfterMs: number | null) => new ApiError(429, null, retryAfterMs);

    test("seconds from the Retry-After (rounded up, at least one), singular and plural; in a moment without one", () => {
      expect(saveErrorMessage(tooFast(1000), t, keys)).toBe("You're doing that too fast. Try again in 1 second.");
      expect(saveErrorMessage(tooFast(2000), t, keys)).toBe("You're doing that too fast. Try again in 2 seconds.");
      expect(saveErrorMessage(tooFast(1500), t, keys)).toBe("You're doing that too fast. Try again in 2 seconds.");
      expect(saveErrorMessage(tooFast(null), t, keys)).toBe("You're doing that too fast. Try again in a moment.");
      expect(saveErrorMessage(tooFast(0), t, keys)).toBe("You're doing that too fast. Try again in a moment.");
    });

    test("Polish plural forms of the seconds", async () => {
      await i18n.changeLanguage("pl");
      try {
        const pl = i18n.t.bind(i18n);
        const say = (seconds: number | null) => saveErrorMessage(tooFast(seconds === null ? null : seconds * 1000), pl, keys);
        expect(say(1)).toBe("Robisz to zbyt szybko. Spróbuj ponownie za 1 sekundę.");
        expect(say(2)).toBe("Robisz to zbyt szybko. Spróbuj ponownie za 2 sekundy.");
        expect(say(4)).toBe("Robisz to zbyt szybko. Spróbuj ponownie za 4 sekundy.");
        expect(say(5)).toBe("Robisz to zbyt szybko. Spróbuj ponownie za 5 sekund.");
        expect(say(12)).toBe("Robisz to zbyt szybko. Spróbuj ponownie za 12 sekund.");
        expect(say(22)).toBe("Robisz to zbyt szybko. Spróbuj ponownie za 22 sekundy.");
        expect(say(60)).toBe("Robisz to zbyt szybko. Spróbuj ponownie za 60 sekund.");
        expect(say(null)).toBe("Robisz to zbyt szybko. Spróbuj ponownie za chwilę.");
      } finally {
        await i18n.changeLanguage("en");
      }
    });

    test("wins over the page's own bucket copy, but a room-busy 429 and other statuses are untouched", () => {
      expect(saveErrorMessage(tooFast(3000), t, keys)).not.toBe(t("threads.message.error.tooManyRequests"));
      expect(saveErrorMessage(new ApiError(429, { type: "urn:adder:problem:room-busy" }, 1000), t, keys)).toBe(t("common.error.roomBusy"));
      expect(saveErrorMessage(new ApiError(500, null), t, keys)).toBe("Create failed (500)");
    });
  });

  test("an unmapped status routes through the generic fallbacks when keys are omitted", () => {
    const minimal = { failed: KEYS.failed } as const;
    expect(saveErrorMessage(new ApiError(404, null), t, minimal)).toBe(t(KEYS.failed));
  });
});

describe("loadErrorMessage", () => {
  test("tags ApiErrors with the status and falls back per class", () => {
    expect(loadErrorMessage(new ApiError(502, null), t)).toBe("Load failed (502)");
    expect(loadErrorMessage(new ApiError(429, null), t)).toBe("Too many requests. Wait a minute and try again.");
    expect(loadErrorMessage(new ApiError(429, { type: "urn:adder:problem:room-busy" }), t)).toBe(t("common.error.roomBusy"));
    expect(loadErrorMessage(new ApiError(429, { type: "urn:adder:problem:read-timeout" }), t)).toBe("The list took too long. Narrow the filter or try again.");
    expect(loadErrorMessage(new ApiError(429, { type: "urn:adder:problem:read-busy" }), t)).toBe("The server is busy. Try again in a moment.");
    expect(loadErrorMessage(timeout, t)).toBe(t("common.error.timeout"));
    expect(loadErrorMessage(new Error("net"), t)).toBe(t("common.error.network"));
  });
});
