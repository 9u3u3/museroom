// Composes each artboard from the shared token sheet plus its own part files,
// so a change to the design system lands in every screen at once.
import { readFileSync, writeFileSync, readdirSync } from "node:fs";

const base = readFileSync("_base.css", "utf8");

// A few ship dark so both skins are visible without touching anything.
const DARK_BY_DEFAULT = new Set(["Main", "Nearby", "Leaderboard", "Logo"]);
const FONTS =
  "https://fonts.googleapis.com/css2?family=Archivo:wght@400;500;600;700;800;900" +
  "&family=Bangers&family=Space+Mono:wght@400;700&display=swap";

// One nav lives in _nav.html; each screen marks its own tab active in CSS.
const nav = readFileSync("parts/_nav.html", "utf8").trimEnd();

const names = readdirSync("parts")
  .filter((f) => !f.startsWith("_"))
  .filter((f) => f.endsWith(".html"))
  .map((f) => f.replace(/\.html$/, ""));

for (const name of names) {
  // Every screen carries the same dark switch, since nothing crosses artboards.
  const startsDark = DARK_BY_DEFAULT.has(name);
  const body = readFileSync(`parts/${name}.html`, "utf8")
    .replace("<!--NAV-->", nav)
    .replace('class="screen', 'class="screen {{theme}}');
  let extra = "";
  try {
    extra = readFileSync(`parts/${name}.css`, "utf8");
  } catch {}

  writeFileSync(
    `${name}.dc.html`,
    `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <link rel="stylesheet" href="${FONTS}">
  <style>
${base}${extra}  </style>
</helmet>
${body.trimEnd()}
</x-dc>
<script data-dc-script data-props='{"dark":{"editor":"boolean","default":${startsDark},"section":"Theme"}}'>
class Component extends DCLogic {
  renderVals() {
    return { theme: this.props.dark ? "dark" : "light" };
  }
}
</script>
</body>
</html>
`,
  );
  console.log(`built ${name}.dc.html`);
}
