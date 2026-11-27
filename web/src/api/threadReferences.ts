import { jsonRequest, timeoutSignal } from "./http";
import type { paths } from "./schema";

type ThreadReferencePage = paths["/api/v1/rooms/{id}/thread-references"]["get"]["responses"]["200"]["content"]["application/json"];

function combinedSignal(caller: AbortSignal): AbortSignal {
  const deadline = timeoutSignal();
  const signals = deadline === undefined ? [caller] : [caller, deadline];
  if (typeof AbortSignal.any === "function") return AbortSignal.any(signals);
  const controller = new AbortController();
  const abort = (signal: AbortSignal) => controller.abort(signal.reason);
  for (const signal of signals) {
    if (signal.aborted) {
      abort(signal);
      break;
    }
    signal.addEventListener("abort", () => abort(signal), { once: true });
  }
  return controller.signal;
}

function validPage(value: unknown, requested: ReadonlySet<number>): value is ThreadReferencePage {
  if (typeof value !== "object" || value === null) return false;
  const page = value as Partial<ThreadReferencePage>;
  if (!Array.isArray(page.items) || page.page !== 1 || page.pageSize !== 100 || page.total !== page.items.length) return false;
  const seen = new Set<number>();
  return page.items.every((item) => {
    const number = item?.number;
    if (!Number.isSafeInteger(number) || number! <= 0 || !requested.has(number!) || seen.has(number!)) return false;
    seen.add(number!);
    return true;
  });
}

/** Resolve a bounded set of on-render numeric references in one authorization-aware room read. */
export async function listThreadReferences(roomId: number, numbers: readonly number[], signal: AbortSignal): Promise<ReadonlySet<number>> {
  const unique = [...new Set(numbers)];
  if (unique.length < 1 || unique.length > 100 || unique.some((number) => !Number.isSafeInteger(number) || number <= 0)) {
    throw new TypeError("Invalid thread-reference request");
  }
  const params = new URLSearchParams();
  unique.forEach((number) => params.append("number", String(number)));
  params.set("page", "1");
  params.set("pageSize", "100");
  const data: unknown = await jsonRequest(`/api/v1/rooms/${roomId}/thread-references?${params}`, { signal: combinedSignal(signal) });
  const requested = new Set(unique);
  if (!validPage(data, requested)) throw new TypeError("Invalid thread-reference response");
  return new Set(data.items.map((item) => item.number));
}
