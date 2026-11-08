// Checks that what the app offers as an update and what the stores show agree with the build,
// instead of leaving app/build.gradle.kts, latest_version.json, RELEASE_NOTES.md and
// metadata/en-US/changelogs/<versionCode>.txt to be kept equal by hand.
//
// Run: node tools/release-metadata-check.mjs   (also a CI job, .github/workflows/pr.yml)
// The version of the app lives in app/build.gradle.kts; everything else is derived from it.
// The rules of the update file are the ones of AGENTS.md, Release.

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const file = (relative) => fileURLToPath(new URL(relative, root));
const read = (relative) => readFileSync(file(relative), 'utf8');

const problems = [];
const check = (ok, message) => {
  if (!ok) problems.push(message);
  return ok;
};

// --- the build, the single source of the version -----------------------------------------------
const gradle = read('app/build.gradle.kts');
const versionCode = Number((gradle.match(/^\s*versionCode\s*=\s*(\d+)/m) || [])[1]);
const versionName = (gradle.match(/^\s*versionName\s*=\s*"([^"]+)"/m) || [])[1];

if (!check(Number.isInteger(versionCode) && versionCode > 0, 'app/build.gradle.kts: no versionCode found')) report();
if (!check(/^\d+\.\d+\.\d+$/.test(versionName || ''), `app/build.gradle.kts: versionName "${versionName}" is not X.Y.Z`)) report();

console.log(`build: versionCode ${versionCode}, versionName ${versionName}`);

// --- the changelog of the update file ----------------------------------------------------------
const update = JSON.parse(read('latest_version.json'));
const changelog = update.changelog || [];

check(changelog.length > 0, 'latest_version.json: the changelog is empty');
if (changelog.length > 0) {
  check(changelog[0].versionCode === versionCode,
    `latest_version.json: the newest changelog entry is versionCode ${changelog[0].versionCode}, the build is ${versionCode}`);
  check(changelog[0].versionName === `v${versionName}`,
    `latest_version.json: the newest changelog entry is ${changelog[0].versionName}, the build is v${versionName}`);
  const codes = changelog.map((entry) => entry.versionCode);
  check(new Set(codes).size === codes.length, 'latest_version.json: a versionCode appears twice in the changelog');
  check(codes.every((code) => code <= versionCode),
    `latest_version.json: the changelog has a versionCode above the build (${versionCode})`);
}

// --- the channels --------------------------------------------------------------------------------
for (const channel of update.channels || []) {
  const name = `channel "${channel.name}"`;
  check(typeof channel.latestVersionName === 'string' && channel.latestVersionName.startsWith('v'),
    `${name}: latestVersionName must start with "v"`);
  check(Number.isInteger(channel.latestVersionCode), `${name}: latestVersionCode is not a number`);
  check(channel.latestVersionCode <= versionCode,
    `${name}: latestVersionCode ${channel.latestVersionCode} is above the build ${versionCode}`);

  // announcing a version whose notes are not in the changelog would update users to something
  // the app then describes with another build's notes
  const entry = changelog.find((item) => item.versionName === channel.latestVersionName);
  if (check(entry !== undefined, `${name}: announces ${channel.latestVersionName}, which the changelog does not have`)) {
    check(entry.versionCode === channel.latestVersionCode,
      `${name}: ${channel.latestVersionName} is versionCode ${channel.latestVersionCode}, the changelog says ${entry.versionCode}`);
  }

  // the urls of the channel itself are the WebView app's, the GeckoView app takes its own
  checkApks(`${name} (the WebView app)`, [channel.url, ...(channel.urls || [])], channel, {
    engine: 'geckoExcluded',
    minAPI: 24,
  });

  for (const [engine, variant] of Object.entries(channel.variants || {})) {
    check(engine === 'geckoIncluded',
      `${name}: variants has "${engine}", only "geckoIncluded" belongs there (the channel itself is the WebView app's)`);
    checkApks(`${name} (the GeckoView app)`, [variant.url, ...(variant.urls || [])], channel, {
      engine: 'geckoIncluded',
      minAPI: variant.minAPI,
    });
  }
}

function checkApks(where, urls, channel, expect) {
  if (!check(urls.some(Boolean), `${where}: no apk url`)) return;
  const announced = channel.latestVersionName.replace(/^v/, '');
  for (const url of urls.filter(Boolean)) {
    check(/^https:\/\/github\.com\/[^/]+\/[^/]+\/releases\/download\/v[\d.]+\//.test(url),
      `${where}: "${url}" is not a GitHub release download url`);
    const fileName = url.split('/').pop() || '';
    check(new RegExp(`^axtvbrowser-${announced.replace('.', '\\.')}-`).test(fileName),
      `${where}: "${fileName}" is not the apk of ${channel.latestVersionName}`);
    check(fileName.includes(expect.engine) && !fileName.includes(expect.engine === 'geckoIncluded' ? 'geckoExcluded' : 'geckoIncluded'),
      `${where}: "${fileName}" is not a ${expect.engine} apk`);
  }
  if (expect.minAPI !== undefined) {
    check(Number.isInteger(expect.minAPI), `${where}: no minAPI`);
    // geckoIncluded can not go below the minSdk of GeckoView, the WebView app keeps the app's own
    if (expect.engine === 'geckoIncluded') check(expect.minAPI === 26, `${where}: minAPI ${expect.minAPI}, GeckoView needs 26`);
    else check(expect.minAPI === 24, `${where}: minAPI ${expect.minAPI}, the WebView app of this build needs 24`);
  }
}

// --- the store metadata ------------------------------------------------------------------------
const dir = 'metadata/en-US/changelogs/';
if (check(existsSync(file(dir)), `no ${dir} directory`)) {
  const slots = readdirSync(file(dir)).filter((name) => name.endsWith('.txt'));
  const numbers = slots.map((name) => Number(name.slice(0, -4)));
  check(numbers.every(Number.isInteger), `${dir}: a file that is not <versionCode>.txt`);
  if (numbers.length > 0) {
    check(Math.max(...numbers) === versionCode,
      `${dir}: the newest slot is ${Math.max(...numbers)}, the build is ${versionCode} — add ${dir}${versionCode}.txt`);
    check(numbers.every((number) => number <= versionCode),
      `${dir}: a slot is above the build versionCode ${versionCode}`);
  }
  for (const [name, text] of slots.map((name) => [name, read(`${dir}${name}`)])) {
    check(text.trim().length > 0, `${dir}${name} is empty`);
    check(text.length <= 500, `${dir}${name} is ${text.length} characters, F-Droid takes 500`);
  }
}

// --- the release notes -------------------------------------------------------------------------
check(read('RELEASE_NOTES.md').trim().length > 0, 'RELEASE_NOTES.md is empty: it is the body of the GitHub release');

report();

function report() {
  if (problems.length > 0) {
    console.error(`\n${problems.length} problem(s) in the release metadata:`);
    for (const problem of problems) console.error(` - ${problem}`);
    process.exit(1);
  }
  console.log(`\nOK: latest_version.json, RELEASE_NOTES.md and metadata/en-US agree with versionCode ${versionCode}.`);
  process.exit(0);
}
