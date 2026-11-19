import { describe, expect, test } from "vitest";
import type { RoomWorkflow } from "../api/rooms";
import {
  activeFilterCount,
  DEFAULT_THREAD_LIST_PARAMS,
  fieldValues,
  filterableFields,
  hasListKeys,
  listSearch,
  ORDER_SORT,
  ORDERS,
  parseThreadListParams,
  pruneFieldEntries,
  rememberedQuery,
  restoreRememberedQuery,
  SCOPE_FILTER,
  SCOPES,
  setFieldValues,
  toApiQuery,
  writeThreadListParams,
  type ThreadListParams,
} from "./threadListParams";

const q = (query: string) => new URLSearchParams(query);

describe("threadListParams", () => {
  test("a bare URL reads as the plain list", () => {
    expect(parseThreadListParams(q(""))).toEqual(DEFAULT_THREAD_LIST_PARAMS);
    expect(DEFAULT_THREAD_LIST_PARAMS).toEqual({ scope: "open", order: "activity", status: [], owner: null, field: [], page: 1 });
  });

  test("the round trip: what a write spells, a read gives back", () => {
    for (const scope of SCOPES) {
      for (const order of ORDERS) {
        for (const page of [1, 2, 37]) {
          const written = writeThreadListParams(q(""), { scope, order, page });
          expect(parseThreadListParams(written)).toEqual({ ...DEFAULT_THREAD_LIST_PARAMS, scope, order, page });
        }
      }
    }
    const filtered = { status: ["triage", "on-hold"], owner: "me", field: ["severity:s1", "severity:s2", "lead:42"] };
    expect(parseThreadListParams(writeThreadListParams(q(""), filtered))).toEqual({ ...DEFAULT_THREAD_LIST_PARAMS, ...filtered });
    expect(parseThreadListParams(writeThreadListParams(q(""), { owner: "42" })).owner).toBe("42");
  });

  test("multi-value keys keep every valid value in URL order, distinct and capped at 20", () => {
    expect(parseThreadListParams(q("status=b&status=BAD&status=a&status=b&field=k%3Av&field=nocolon&field=k%3Av&field=k%3A7")).status).toEqual(["b", "a"]);
    // The parser cannot know a field's type, so it keeps both value shapes (an option key, a user id); `toApiQuery` judges them against the workflow.
    expect(parseThreadListParams(q("field=k%3Av&field=nocolon&field=k%3Av&field=k%3A7&field=%3Ax")).field).toEqual(["k:v", "k:7"]);
    const many = Array.from({ length: 30 }, (_, i) => `status=s${i}`).join("&");
    expect(parseThreadListParams(q(many)).status).toHaveLength(20);
    expect(parseThreadListParams(q(`status=${"a".repeat(41)}`)).status).toEqual([]);
    expect(parseThreadListParams(q("owner=3&owner=4")).owner).toBe("3");
  });

  test("a write spells status and field as repeated keys between order and page, and a change resets the page", () => {
    expect(writeThreadListParams(q("page=3&message=1"), { status: ["a", "b"], owner: "me", field: ["k:v"] }).toString()).toBe(
      "message=1&status=a&status=b&owner=me&field=k%3Av",
    );
    expect(writeThreadListParams(q("status=a&status=b"), { status: [] }).toString()).toBe("");
  });

  test("the remembered query and the link keys carry the filters", () => {
    expect(rememberedQuery(q("status=a&owner=me&field=k%3Av&page=2"))).toBe("status=a&owner=me&field=k%3Av");
    expect(listSearch(q("status=a&status=zzZ&page=2")).toString()).toBe("status=a&page=2");
    expect(hasListKeys(q("owner=me"))).toBe(true);
    expect(restoreRememberedQuery(q(""), "status=a&status=b&owner=7").toString()).toBe("status=a&status=b&owner=7");
  });

  describe("the request", () => {
    const workflow = {
      revision: 1,
      statuses: [],
      fields: [
        { key: "severity", label: "Severity", type: "SELECT", required: false, options: [{ key: "s1", label: "S1" }] },
        { key: "labels", label: "Labels", type: "MULTI_SELECT", required: false, options: [] },
        { key: "lead", label: "Lead", type: "USER", required: false, options: [] },
        { key: "notes", label: "Notes", type: "TEXT", required: false, options: [] },
        { key: "due", label: "Due", type: "DATE", required: false, options: [] },
      ],
    } as const satisfies RoomWorkflow;
    const params = (patch: Partial<ThreadListParams>) => ({ ...DEFAULT_THREAD_LIST_PARAMS, ...patch });

    test("the plain list asks for open threads by activity and nothing else, with or without a workflow", () => {
      const plain = { closed: false, sort: "-lastActivityAt", status: undefined, ownerId: undefined, field: undefined };
      expect(toApiQuery(params({}), undefined, 7)).toEqual(plain);
      expect(toApiQuery(params({}), workflow, 7)).toEqual(plain);
    });

    test("owner=me is the session's id, a number is itself, and me without a session is no filter", () => {
      expect(toApiQuery(params({ owner: "me" }), undefined, 7)?.ownerId).toEqual([7]);
      expect(toApiQuery(params({ owner: "42" }), undefined, 7)?.ownerId).toEqual([42]);
      expect(toApiQuery(params({ owner: "me" }), undefined, null)?.ownerId).toBeUndefined();
    });

    test("statuses, the all scope and the workflow order are sent as they are", () => {
      expect(toApiQuery(params({ status: ["triage"], scope: "all", order: "status" }), undefined, 1)).toMatchObject({ status: ["triage"], closed: undefined, sort: "status" });
    });

    test("field filters wait for the workflow, and a key that is not an active filterable field is dropped, not sent", () => {
      expect(toApiQuery(params({ field: ["severity:s1"] }), undefined, 1)).toBeNull();
      const query = toApiQuery(params({ field: ["severity:s1", "severity:s2", "gone:x", "notes:x", "due:x", "lead:42", "labels:a"] }), workflow, 1);
      expect(query?.field).toEqual(["severity:s1", "severity:s2", "lead:42", "labels:a"]);
      expect(toApiQuery(params({ field: ["gone:x"] }), workflow, 1)?.field).toBeUndefined();
    });

    test("a value of the wrong shape for its field's type is dropped too: a user id on a SELECT, a word on a USER field", () => {
      const query = toApiQuery(params({ field: ["severity:7", "severity:s1", "labels:7", "labels:a", "lead:abc", "lead:42", "lead:s1"] }), workflow, 1);
      expect(query?.field).toEqual(["severity:s1", "labels:a", "lead:42"]);
      expect(toApiQuery(params({ field: ["severity:7"] }), workflow, 1)?.field).toBeUndefined();
      expect(toApiQuery(params({ field: ["lead:abc"] }), workflow, 1)?.field).toBeUndefined();
    });

    test("pruneFieldEntries keeps what the workflow can judge, in order", () => {
      expect(pruneFieldEntries(["gone:x", "severity:s2", "notes:x", "lead:0", "lead:9", "severity:S1"], workflow)).toEqual(["severity:s2", "lead:9"]);
      expect(pruneFieldEntries([], workflow)).toEqual([]);
    });

    test("filterableFields is the active SELECT, MULTI_SELECT and USER fields", () => {
      expect(filterableFields(workflow).map((field) => field.key)).toEqual(["severity", "labels", "lead"]);
      expect(filterableFields(undefined)).toEqual([]);
    });

    test("field values are read and replaced per key, and the entries stay capped", () => {
      const fields = ["severity:s1", "lead:42", "severity:s2"];
      expect(fieldValues(fields, "severity")).toEqual(["s1", "s2"]);
      expect(fieldValues(fields, "labels")).toEqual([]);
      expect(setFieldValues(fields, "severity", ["s3"])).toEqual(["lead:42", "severity:s3"]);
      expect(setFieldValues(fields, "lead", [])).toEqual(["severity:s1", "severity:s2"]);
      expect(setFieldValues([], "k", Array.from({ length: 25 }, (_, i) => `v${i}`))).toHaveLength(20);
    });

    test("the active count is the statuses, the owner and each field key with a value", () => {
      expect(activeFilterCount({})).toBe(0);
      expect(activeFilterCount({ status: ["a", "b"], ownerId: [7], field: ["k:1", "k:2", "j:3"] })).toBe(4);
    });
  });

  test("defaults are omitted from what a write spells", () => {
    expect(writeThreadListParams(q(""), { scope: "open", order: "activity", page: 1 }).toString()).toBe("");
    expect(writeThreadListParams(q(""), { scope: "closed" }).toString()).toBe("scope=closed");
    expect(writeThreadListParams(q(""), { scope: "all", order: "number", page: 3 }).toString()).toBe("scope=all&order=number&page=3");
    expect(writeThreadListParams(q("scope=closed"), { scope: "open" }).toString()).toBe("");
  });

  test.each([
    ["scope=weird", "scope"],
    ["scope=OPEN", "scope"],
    ["scope=", "scope"],
    ["order=oldest", "order"],
    ["order=sideways", "order"],
    ["status=Triage", "status"],
    ["status=a%3Ab", "status"],
    ["owner=0", "owner"],
    ["owner=007", "owner"],
    ["owner=99999999999", "owner"],
    ["owner=you", "owner"],
    ["field=severity", "field"],
    ["field=%3As1", "field"],
    ["field=severity%3A", "field"],
    ["field=Severity%3As1", "field"],
    ["field=severity%3AS1", "field"],
    ["page=0", "page"],
    ["page=-2", "page"],
    ["page=2x", "page"],
    ["page=1.5", "page"],
    ["page=1e3", "page"],
    ["page=", "page"],
    ["page=99999999999", "page"],
  ])("junk (%s) reads as the default", (query, key) => {
    const parsed = parseThreadListParams(q(query));
    expect(parsed[key as keyof typeof parsed]).toEqual(DEFAULT_THREAD_LIST_PARAMS[key as keyof typeof parsed]);
  });

  test("a repeated key reads its first value", () => {
    expect(parseThreadListParams(q("scope=closed&scope=all")).scope).toBe("closed");
  });

  test("a change resets the page in the same write; a page change keeps the rest", () => {
    expect(writeThreadListParams(q("scope=closed&page=4"), { order: "number" }).toString()).toBe("scope=closed&order=number");
    expect(writeThreadListParams(q("scope=closed&page=4"), { scope: "all" }).toString()).toBe("scope=all");
    expect(writeThreadListParams(q("scope=closed&order=newest"), { page: 5 }).toString()).toBe("scope=closed&order=newest&page=5");
    expect(writeThreadListParams(q("scope=closed&page=4"), { page: 1 }).toString()).toBe("scope=closed");
  });

  test("a write drops junk list values and keeps every key that is not the list's", () => {
    expect(writeThreadListParams(q("scope=junk&message=9&focus=1&page=oops"), { order: "newest" }).toString()).toBe("message=9&focus=1&order=newest");
  });

  test("hasListKeys sees a list key, valid or not, and nothing else", () => {
    expect(hasListKeys(q(""))).toBe(false);
    expect(hasListKeys(q("message=9&focus=1"))).toBe(false);
    expect(hasListKeys(q("scope=junk"))).toBe(true);
    expect(hasListKeys(q("page=2"))).toBe(true);
  });

  test("listSearch is the normalized list keys, page included, nothing else", () => {
    expect(listSearch(q("scope=closed&page=3&message=9&order=bad")).toString()).toBe("scope=closed&page=3");
    expect(listSearch(q("message=9")).toString()).toBe("");
  });

  test("the remembered query leaves the page out, is empty for the plain list, and restores onto other keys", () => {
    expect(rememberedQuery(q("scope=closed&order=number&page=4"))).toBe("scope=closed&order=number");
    expect(rememberedQuery(q("page=4"))).toBe("");
    expect(restoreRememberedQuery(q("message=9"), "scope=closed&order=number").toString()).toBe("message=9&scope=closed&order=number");
    expect(restoreRememberedQuery(q(""), "scope=garbage").toString()).toBe("");
    // The page is never remembered, even when a stored value was edited by hand.
    expect(restoreRememberedQuery(q("page=2"), "scope=closed&page=4").toString()).toBe("scope=closed");
  });

  test("every scope and order has its server side: the closed filter and a whitelisted sort", () => {
    expect(SCOPE_FILTER).toEqual({ open: false, closed: true, all: undefined });
    expect(ORDER_SORT).toEqual({ activity: "-lastActivityAt", newest: "-createdAt", number: "number", status: "status" });
  });
});
