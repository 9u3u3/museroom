// Composes each artboard from the shared token sheet plus its own part files,
// so a change to the design system lands in every screen at once.
import { readFileSync, writeFileSync, readdirSync } from "node:fs";

const base = readFileSync("_base.css", "utf8");

// A few ship dark so both skins are visible without touching anything.
const DARK_BY_DEFAULT = new Set([
  "Main", "Nearby", "Leaderboard", "Logo",
  // The player surfaces ship dark: they are what is on screen with the lights
  // off, and the artwork is the only bright thing on them.
  "Player", "Lyrics", "Queue", "Room",
]);
const FONTS =
  "https://fonts.googleapis.com/css2?family=Archivo:wght@400;500;600;700;800;900" +
  "&family=Bangers&family=Space+Mono:wght@400;700&display=swap";

// One nav lives in _nav.html; each screen marks its own tab active in CSS.
const nav = readFileSync("parts/_nav.html", "utf8").trimEnd();

// The mini player is the one piece that sits on several tabs at once, so it is
// composed in rather than copied into each screen that shows it.
const mini = readFileSync("parts/_mini.html", "utf8").trimEnd();

const names = readdirSync("parts")
  .filter((f) => !f.startsWith("_"))
  .filter((f) => f.endsWith(".html"))
  .map((f) => f.replace(/\.html$/, ""));

for (const name of names) {
  // Every screen carries the same dark switch, since nothing crosses artboards.
  const startsDark = DARK_BY_DEFAULT.has(name);
  const body = readFileSync(`parts/${name}.html`, "utf8")
    .replace("<!--NAV-->", nav)
    .replace("<!--MINI-->", mini)
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

// A single self-contained page holding every screen at once, so the design can
// be opened from the repo with nothing installed and nothing running. The
// canvas is the place to edit; this is the place to look.
const canvas = JSON.parse(readFileSync("canvas.json", "utf8"));
const titleOf = new Map(
  canvas.artboards.map((a) => [a.file.replace(/\.dc\.html$/, ""), a.title]),
);
let sheet =
  `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Museroom screens</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="${FONTS}">
<style>
  body{margin:0; padding:28px; background:#6E6A62}
  body > .pv-title, body > .pv-lede, body > .page, .cap{
    font-family:"Archivo",Helvetica,Arial,sans-serif}
  .pv-title{margin:0 0 4px; font-family:"Bangers",Impact,sans-serif; font-size:38px;
     letter-spacing:.04em; color:#FBF6EA; text-shadow:4px 4px 0 #14110D}
  .pv-lede{margin:0 0 26px; max-width:62ch; color:#FBF6EA; opacity:.82;
        font-size:13px; font-weight:700; line-height:1.5}
  .page{margin:34px 0 14px; font-size:11px; font-weight:900; letter-spacing:.2em;
        text-transform:uppercase; color:#FBF6EA; opacity:.6}
  .sheet{display:flex; flex-wrap:wrap; gap:26px}
  .frame{display:flex; flex-direction:column; gap:8px}
  .cap{font-size:11px; font-weight:900; letter-spacing:.12em; text-transform:uppercase;
       color:#FBF6EA; opacity:.85}
${base}</style>
</head>
<body>
<h1>MUSEROOM SCREENS</h1>
<p class="lede">Every artboard on one page, built from the same parts as the
canvas. Open it straight from the repo. Edit the parts, run
<code>node design/build.mjs</code>, and this regenerates with them.</p>
`;
for (const page of canvas.pages) {
  const on = canvas.artboards.filter((a) => a.page === page.id);
  if (!on.length) continue;
  sheet += `<div class="page">${page.name}</div>\n<div class="sheet">\n`;
  for (const a of on) {
    const name = a.file.replace(/\.dc\.html$/, "");
    let extra = "";
    try {
      extra = readFileSync(`parts/${name}.css`, "utf8");
    } catch {}
    const markup = readFileSync(`parts/${name}.html`, "utf8")
      .replace("<!--NAV-->", nav)
      .replace("<!--MINI-->", mini)
      .replace(
        'class="screen',
        `class="screen ${DARK_BY_DEFAULT.has(name) ? "dark" : ""} P${name} `,
      );
    // Each screen's own rules are scoped to that screen, because on one page
    // sixteen sheets that all define .body would otherwise be one sheet.
    // Walked rather than matched with a regex: selectors are only rewritten at
    // the top level, so the insides of an @keyframes stay the percentages they
    // are, and a rule is never skipped for sharing a brace with its neighbour.
    const scoped = scopeCss(extra, `.P${name}`);
    sheet += `<style>${scoped}</style>\n<div class="frame"><span class="cap">${titleOf.get(name) ?? name}</span>\n${markup.trimEnd()}\n</div>\n`;
  }
  sheet += `</div>\n`;
}
writeFileSync("preview.html", sheet + "</body>\n</html>\n");
console.log("built preview.html");

// Prefix every top-level selector in one screen's stylesheet, leaving at-rules
// and anything nested inside them untouched.
function scopeCss(css, prefix) {
  let out = "";
  let depth = 0;
  let start = 0;
  for (let i = 0; i < css.length; i++) {
    const c = css[i];
    if (c === "{") {
      if (depth === 0) {
        const head = css.slice(start, i);
        const sel = head.trim();
        // An at-rule keeps its own head; its body is copied through verbatim.
        out +=
          sel.startsWith("@") || sel === ""
            ? head
            : head.replace(
                sel,
                sel
                  .split(",")
                  .map((s) => {
                    const t = s.trim();
                    return t.startsWith(".screen")
                      ? prefix + t.slice(".screen".length)
                      : `${prefix} ${t}`;
                  })
                  .join(", "),
              );
        out += "{";
        start = i + 1;
      }
      depth++;
    } else if (c === "}") {
      depth--;
      if (depth === 0) {
        out += css.slice(start, i + 1);
        start = i + 1;
      }
    }
  }
  return out + css.slice(start);
}
