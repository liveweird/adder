// Personal landing-page summary — one bounded read, with each section capped by the server.

import { jsonRequest } from "./http";
import type { components } from "./schema";

export type HomeResponse = components["schemas"]["HomeResponse"];
export type HomeQuote = components["schemas"]["HomeQuote"];
export type HomeThread = components["schemas"]["HomeThread"];
export type HomeRoom = components["schemas"]["HomeRoom"];

export function getHome(): Promise<HomeResponse> {
  return jsonRequest<HomeResponse>("/api/v1/home");
}
