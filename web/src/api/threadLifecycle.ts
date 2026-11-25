import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { ThreadPage, ThreadResponse } from "./threads";

export type LifecycleThread = ThreadResponse;
export type MergedThreadPage = ThreadPage;

export async function getLifecycleThread(roomId: number, number: number): Promise<LifecycleThread> {
  return jsonRequest<LifecycleThread>(`/api/v1/rooms/${roomId}/threads/${number}`);
}

export async function deleteThread(roomId: number, number: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${roomId}/threads/${number}`, { method: "DELETE" });
}

export async function mergeThread(roomId: number, number: number, targetNumber: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${roomId}/threads/${number}/merge`, {
    method: "PUT",
    body: JSON.stringify({ targetNumber }),
  });
}

export async function listMergedThreads(roomId: number, targetNumber: number, page: number, pageSize: number): Promise<MergedThreadPage> {
  const params = buildQuery({ page, pageSize, sort: "number", mergedInto: targetNumber });
  return jsonRequest<MergedThreadPage>(`/api/v1/rooms/${roomId}/threads?${params}`);
}
