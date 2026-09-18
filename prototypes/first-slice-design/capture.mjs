import { chromium } from "playwright";
import fs from "node:fs/promises";

const base = "http://127.0.0.1:4173/";
const out = "prototypes/first-slice-design/rendered-proof";
await fs.mkdir(out, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1600, height: 1100 }, deviceScaleFactor: 1 });

await page.goto(base, { waitUntil: "networkidle" });

async function choose(selector, value) {
  await page.locator(`${selector}="${value}"]`).click();
  await page.waitForTimeout(80);
}

async function setScreen(screen) {
  await page.locator(`[data-screen="${screen}"]`).click();
  await page.waitForTimeout(80);
}

async function setState(mode) {
  await page.locator(`[data-runtime-state="${mode}"]`).click();
  await page.waitForTimeout(80);
}

async function setView(view) {
  await page.locator(`[data-view="${view}"]`).click();
  await page.waitForTimeout(80);
}

async function setLang(lang) {
  await page.locator(`[data-lang="${lang}"]`).click();
  await page.waitForTimeout(80);
}

async function shot(name, { screen, mode, view, lang }) {
  await setScreen(screen);
  await setView(view);
  await setLang(lang);
  await setState(mode);
  await page.locator("#device").screenshot({ path: `${out}/${name}.png` });
  console.log("CAPTURED", name);
}

const cases = [
  ["M06-A-tech-ready-en-phone",{screen:"technician",mode:"ready",view:"phone",lang:"en"}],
  ["M06-B-tech-offline-en-phone",{screen:"technician",mode:"offline",view:"phone",lang:"en"}],
  ["M06-C-tech-conflict-en-phone",{screen:"technician",mode:"conflict",view:"phone",lang:"en"}],
  ["M06-D-tech-rework-en-phone",{screen:"technician",mode:"rework",view:"phone",lang:"en"}],
  ["R02-tech-ready-ar-phone",{screen:"technician",mode:"ready",view:"phone",lang:"ar"}],
  ["R02-tech-conflict-ar-phone",{screen:"technician",mode:"conflict",view:"phone",lang:"ar"}],
  ["T01-tech-offline-en-tablet",{screen:"technician",mode:"offline",view:"tablet",lang:"en"}],

  ["M07-A-warehouse-available-en-phone",{screen:"warehouse",mode:"available",view:"phone",lang:"en"}],
  ["M07-C-warehouse-calibration-en-phone",{screen:"warehouse",mode:"calibration",view:"phone",lang:"en"}],
  ["M07-E-warehouse-collision-en-phone",{screen:"warehouse",mode:"collision",view:"phone",lang:"en"}],
  ["M07-F-warehouse-success-en-phone",{screen:"warehouse",mode:"success",view:"phone",lang:"en"}],
  ["R06-warehouse-available-ar-phone",{screen:"warehouse",mode:"available",view:"phone",lang:"ar"}],
  ["R06-warehouse-collision-ar-phone",{screen:"warehouse",mode:"collision",view:"phone",lang:"ar"}],
  ["T02-warehouse-collision-en-tablet",{screen:"warehouse",mode:"collision",view:"tablet",lang:"en"}],

  ["D06-A-config-active-en-desktop",{screen:"config",mode:"active",view:"desktop",lang:"en"}],
  ["D06-B-config-draft-en-desktop",{screen:"config",mode:"draft",view:"desktop",lang:"en"}],
  ["D06-C-config-invalid-en-desktop",{screen:"config",mode:"invalid",view:"desktop",lang:"en"}],
  ["D06-E-config-conflict-en-desktop",{screen:"config",mode:"conflict",view:"desktop",lang:"en"}],
  ["R07-config-draft-ar-desktop",{screen:"config",mode:"draft",view:"desktop",lang:"ar"}],

  ["D07-A-review-clean-en-desktop",{screen:"review",mode:"clean",view:"desktop",lang:"en"}],
  ["D07-B-review-missing-en-desktop",{screen:"review",mode:"missing",view:"desktop",lang:"en"}],
  ["D07-C-review-rework-en-desktop",{screen:"review",mode:"rework",view:"desktop",lang:"en"}],
  ["D07-D-review-stale-en-desktop",{screen:"review",mode:"stale",view:"desktop",lang:"en"}],
  ["R08-review-clean-ar-desktop",{screen:"review",mode:"clean",view:"desktop",lang:"ar"}],

  ["D02-A-project-healthy-en-desktop",{screen:"project",mode:"healthy",view:"desktop",lang:"en"}],
  ["D02-B-project-attention-en-desktop",{screen:"project",mode:"attention",view:"desktop",lang:"en"}],
  ["D02-C-project-critical-en-desktop",{screen:"project",mode:"critical",view:"desktop",lang:"en"}],
  ["D02-D-project-hold-en-desktop",{screen:"project",mode:"hold",view:"desktop",lang:"en"}],
  ["R04-project-critical-ar-desktop",{screen:"project",mode:"critical",view:"desktop",lang:"ar"}]
];

for (const [name, cfg] of cases) await shot(name, cfg);

await browser.close();
console.log(`HILTECH_DESIGN_RENDER_PASS captures=${cases.length}`);
