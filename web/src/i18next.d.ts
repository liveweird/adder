// Typed translation keys: every t()/i18nKey is checked against the EN resource tree
// (the canonical key set — PL parity is enforced separately by locales/parity.test.ts).
// Dynamic keys built from unions type-check when the union is statically known; a field that holds a
// key is typed `ParseKeys` (from i18next), never `string` (web/CLAUDE.md "Internationalization").
import type { en } from "./i18n";

declare module "i18next" {
  interface CustomTypeOptions {
    defaultNS: "translation";
    resources: {
      translation: typeof en;
    };
  }
}
