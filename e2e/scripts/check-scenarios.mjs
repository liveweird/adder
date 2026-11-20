// The scenario-parity gate (2026-08 review round): every spec has its scenario file and every
// test() title appears as a `## Scenario:` heading VERBATIM — and vice versa (the compiler
// contract in scenarios/README.md, previously verified by hand at every checkup).
// Every spec is covered, accessibility.spec.ts included. A title may be written with double
// quotes, single quotes or backticks. A backtick title with `${…}` holes is a TEMPLATE (the
// registered exception: accessibility.spec.ts generates tests from lists): it has no literal
// text to mirror, so the gate asks that at least one scenario heading fits it (each hole
// matching any text), and a heading that fits no literal title may fit a template instead.
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const testsDir = join(root, "tests");
const scenariosDir = join(root, "scenarios");

// Matches a top-level test declaration's opening quote (also when the title starts on the next line);
// modifier calls (`test.skip(`, `test.only(`, `test.describe(`) don't match — `test` is followed by a
// dot, not by `(`.
const TITLE_START_RE = /(?<![.\w])test\(\s*(["'`])/g;
// The body of a title quoted with `q`: the unrolled `[^q\\]*(?:\\.[^q\\]*)*` form — linear, no alternation backtracking.
const bodyRe = (q) => new RegExp(`^([^${q}\\\\]*(?:\\\\.[^${q}\\\\]*)*)${q}`);

const unescape = (s) => s.replace(/\\(.)/g, "$1");
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// Every test() title of a spec source: `{ literal }` for a plain string, `{ template, text }` for a
// backtick title with holes.
function extractTitles(src) {
  const titles = [];
  for (const m of src.matchAll(TITLE_START_RE)) {
    const quote = m[1];
    const body = bodyRe(quote).exec(src.slice(m.index + m[0].length))?.[1];
    if (body === undefined) {
      // An unterminated title would be skipped silently and its test would escape the parity check.
      throw new Error(`test() title with no closing ${quote} near offset ${m.index}; the checker cannot read it`);
    }
    if (quote === "`" && body.includes("${")) {
      // Split on the holes (none of them nests a brace in this suite), escape the literal parts between them.
      const pattern = body
        .split(/\$\{[^}]*\}/)
        .map((part) => escapeRe(unescape(part)))
        .join(".*");
      titles.push({ template: new RegExp(`^${pattern}$`), text: body });
    } else {
      titles.push({ literal: unescape(body) });
    }
  }
  return titles;
}

let failures = 0;
const fail = (msg) => {
  failures += 1;
  console.error(`✗ ${msg}`);
};

const specs = readdirSync(testsDir).filter((f) => f.endsWith(".spec.ts"));
const scenarioFiles = new Set(
  readdirSync(scenariosDir).filter((f) => f.endsWith(".md") && f !== "README.md"),
);

for (const spec of specs) {
  const base = spec.replace(/\.spec\.ts$/, "");
  const scenarioName = `${base}.md`;
  if (!scenarioFiles.has(scenarioName)) {
    fail(`${spec}: missing scenarios/${scenarioName}`);
    continue;
  }
  scenarioFiles.delete(scenarioName);

  const src = readFileSync(join(testsDir, spec), "utf8");
  const titles = extractTitles(src);
  const scenario = readFileSync(join(scenariosDir, scenarioName), "utf8");
  const headings = [...scenario.matchAll(/^## Scenario: (.+)$/gm)].map((m) => m[1].trimEnd());

  for (const t of titles) {
    if (t.literal !== undefined) {
      if (!headings.includes(t.literal)) fail(`${spec}: title has no matching heading in ${scenarioName}:\n    "${t.literal}"`);
    } else if (!headings.some((h) => t.template.test(h))) {
      fail(`${spec}: template title has no heading that fits it in ${scenarioName}:\n    \`${t.text}\``);
    }
  }
  for (const h of headings) {
    const fits = titles.some((t) => (t.literal !== undefined ? t.literal === h : t.template.test(h)));
    if (!fits) fail(`scenarios/${scenarioName}: heading matches no test() title in ${spec}:\n    "${h}"`);
  }
  if (titles.length === 0) fail(`${spec}: no test() titles extracted — the checker's regex needs a look`);
}

for (const orphan of scenarioFiles) {
  fail(`scenarios/${orphan}: no matching tests/${orphan.replace(/\.md$/, ".spec.ts")}`);
}

if (failures === 0) {
  console.log(`✓ scenario parity: ${specs.length} specs ↔ scenarios in sync`);
} else {
  console.error(`${failures} scenario-parity failure(s)`);
  process.exit(1);
}
