#!/usr/bin/env node
'use strict';

/*
 * Oracle-execution harness for the parity kernel (slice 001, task T028).
 *
 * WHAT IT DOES
 *   Runs the legacy HMPPS redaction filter — cpp-context-azure-legalaidagency at commit
 *   122e2152, azure-functions/durable-functions/ProbationHearingResultedFilter/functions.js
 *   (the exported `probationFilter`) — over a given hearing payload, with the youth-filter
 *   switch forced to a fixed value rather than looked up (FR-001-022). It classifies the
 *   result the way the legacy publisher treats it (an empty result is "no message";
 *   HMPPSExternalPublisher/index.js:68-70), writes any body it produced, and records the
 *   provenance meta.json needs (the legacy commit, the Node/npm runtime) in the shape
 *   data-model.md's CorpusCase expectations/provenance fields expect.
 *
 * WHY IT LIVES OUTSIDE THE GRADLE BUILD
 *   The oracle is JavaScript and must be run by a JS runtime, not the JVM. Per the
 *   constitution and FR-001-022 the legacy is never executed inside this repository's build;
 *   its recordings are committed with their provenance and are never regenerated to make a
 *   test pass. This script IS that recorder. Every ACTIVE / synthetic expectation authored
 *   into src/test/resources/corpus/ is produced here, never hand-written.
 *
 * IT HAS NO NPM DEPENDENCIES OF ITS OWN
 *   functions.js requires lodash; that require resolves against the legacy repo's own
 *   node_modules, so there is nothing to `npm install` here. Node 17+ is required
 *   (structuredClone).
 *
 * PRIVACY
 *   The payload and any produced body are corpus content (FR-001-031): they are written to
 *   files under the case directory you point at, never echoed to stdout. stdout carries only
 *   value-free metadata — the classification, file paths, digests and provenance. The
 *   legacy's final log line (R12, DV-09) is swallowed by a no-op context.
 *
 * USAGE
 *   node tools/oracle-harness/run-oracle.js \
 *     --payload <path-to-hearing-payload.json> \
 *     --out-dir <case-directory> \
 *     [--switch ACTIVE|INACTIVE|BOTH]   (default: BOTH; case-insensitive) \
 *     [--form CACHE|FALLBACK]           (default: CACHE; metadata label only) \
 *     [--hearing-id <id>]               (default: payload.hearing.id, else UNKNOWN) \
 *     [--legacy-repo <path>]            (default: ../cpp-context-azure-legalaidagency,
 *                                        or $LEGACY_REPO)
 *
 * OUTPUT (under --out-dir)
 *   expectations/active.json    the redacted body, when the ACTIVE run produced one
 *   expectations/inactive.json  the redacted body, when the INACTIVE run produced one
 *   oracle.json                 provenance + one expectation entry per switch state run,
 *                               ready to merge into the case's meta.json
 *
 * EXIT CODES
 *   0 success; non-zero on bad arguments, a missing/mismatched oracle, or an I/O error.
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
// Referenced through the module object (not a destructured binding) so a self-test can stub
// childProcess.execFileSync to exercise resolveLegacyRepo/readRuntimeProvenance without a checkout.
const childProcess = require('child_process');

const EXPECTED_LEGACY_COMMIT_PREFIX = '122e2152';
const LEGACY_FILTER_RELATIVE_PATH =
  'azure-functions/durable-functions/ProbationHearingResultedFilter/functions.js';

function fail(message) {
  process.stderr.write(`oracle-harness: ${message}\n`);
  process.exit(1);
}

function parseArgs(argv) {
  const args = {
    switch: 'BOTH',
    form: 'CACHE',
    legacyRepo: process.env.LEGACY_REPO || null,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    const next = () => {
      const value = argv[i + 1];
      if (value === undefined) {
        fail(`missing value for ${token}`);
      }
      i += 1;
      return value;
    };
    switch (token) {
      case '--payload':
        args.payload = next();
        break;
      case '--out-dir':
        args.outDir = next();
        break;
      case '--switch':
        args.switch = next().toUpperCase();
        break;
      case '--form':
        args.form = next().toUpperCase();
        break;
      case '--hearing-id':
        args.hearingId = next();
        break;
      case '--legacy-repo':
        args.legacyRepo = next();
        break;
      case '-h':
      case '--help':
        process.stdout.write(fs.readFileSync(__filename, 'utf8').split('\n')
          .filter((line) => line.startsWith(' *') || line.startsWith('/*'))
          .join('\n') + '\n');
        process.exit(0);
        break;
      default:
        fail(`unknown argument: ${token}`);
    }
  }
  if (!args.payload) {
    fail('--payload is required');
  }
  if (!args.outDir) {
    fail('--out-dir is required');
  }
  if (!['ACTIVE', 'INACTIVE', 'BOTH'].includes(args.switch)) {
    fail(`--switch must be ACTIVE, INACTIVE or BOTH (got ${args.switch})`);
  }
  if (!['CACHE', 'FALLBACK'].includes(args.form)) {
    fail(`--form must be CACHE or FALLBACK (got ${args.form})`);
  }
  return args;
}

function resolveLegacyRepo(explicit) {
  const candidates = [];
  if (explicit) {
    candidates.push(path.resolve(explicit));
  }
  // Default: the workspace sibling checkout, two levels above this tool directory's repo root.
  candidates.push(path.resolve(__dirname, '..', '..', '..', 'cpp-context-azure-legalaidagency'));
  for (const candidate of candidates) {
    if (fs.existsSync(path.join(candidate, LEGACY_FILTER_RELATIVE_PATH))) {
      return candidate;
    }
  }
  fail(
    `could not find the legacy filter at <repo>/${LEGACY_FILTER_RELATIVE_PATH}. `
      + `Tried: ${candidates.join(', ')}. Pass --legacy-repo or set $LEGACY_REPO.`,
  );
  return null;
}

function gitOutput(repo, gitArgs) {
  return childProcess.execFileSync('git', ['-C', repo, ...gitArgs], { encoding: 'utf8' }).trim();
}

function readRuntimeProvenance(legacyRepo) {
  const legacyCommit = gitOutput(legacyRepo, ['rev-parse', 'HEAD']);
  if (!legacyCommit.startsWith(EXPECTED_LEGACY_COMMIT_PREFIX)) {
    fail(
      `legacy repo is at ${legacyCommit}, not the pinned oracle `
        + `${EXPECTED_LEGACY_COMMIT_PREFIX}. Check out the pinned commit before recording.`,
    );
  }
  const legacyTreeClean = gitOutput(legacyRepo, ['status', '--porcelain']) === '';
  let npmVersion = null;
  try {
    npmVersion = childProcess.execFileSync('npm', ['--version'], { encoding: 'utf8' }).trim();
  } catch (err) {
    // npm is optional provenance; its absence is recorded, never fatal, and never silently
    // turned into a success value — the field is explicitly null with a note.
    npmVersion = null;
  }
  return {
    source: 'EXECUTED_LEGACY',
    legacyRepo: 'cpp-context-azure-legalaidagency',
    legacyCommit,
    legacyTreeClean,
    legacyEntryPoint: `${LEGACY_FILTER_RELATIVE_PATH}#probationFilter`,
    nodeVersion: process.version,
    npmVersion,
    npmVersionNote: npmVersion === null ? 'npm not resolvable on PATH at record time' : undefined,
    recordedAtUtc: new Date().toISOString(),
  };
}

// Reproduce the legacy JS truthiness of the three box-hearing flags, so an empty result can be
// attributed to R1 (the SJP/box collapse) rather than R11 (nothing survived). The check mirrors
// filterSJPCaseWhichAreResultedInSJPSessionAndBoxHearing in functions.js; it never re-implements a
// removal rule, only distinguishes which collapse the legacy took.
function wasEmptiedByR1(payload) {
  const hearing = payload && payload.hearing;
  if (!hearing) {
    return false;
  }
  return Boolean(hearing.isSJPHearing || hearing.isBoxHearing || hearing.isVirtualBoxHearing);
}

function firstLegacyFrame(stack) {
  if (!stack) {
    return null;
  }
  for (const line of stack.split('\n')) {
    const match = line.match(/at\s+(\w+)\s+\(.*functions\.js:\d+:\d+\)/);
    if (match) {
      return match[1];
    }
  }
  return null;
}

async function runOne(probationFilter, payload, switchState, hearingId) {
  const toggle = switchState === 'ACTIVE';
  const input = structuredClone(payload); // probationFilter mutates its argument in place.
  const noopContext = { log: () => {} }; // swallow R12 (DV-09); it carries the payload.
  try {
    const result = await probationFilter(input, noopContext, hearingId, toggle);
    const empty = result === null || result === undefined
      || (typeof result === 'object' && Object.keys(result).length === 0);
    if (empty) {
      return {
        result: 'NO_MESSAGE',
        noMessageKind: wasEmptiedByR1(payload) ? 'EMPTIED_BY_R1' : 'EMPTIED_BY_R11',
        body: null,
      };
    }
    return { result: 'BODY', noMessageKind: null, body: result };
  } catch (err) {
    return {
      result: 'RULES_FAILURE',
      noMessageKind: 'RULES_FAILURE',
      body: null,
      thrownErrorName: err && err.name ? err.name : null,
      thrownErrorMessage: err && err.message ? err.message : null,
      thrownInFunction: err ? firstLegacyFrame(err.stack) : null,
    };
  }
}

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

function writeBody(outDir, switchState, body) {
  const relative = path.join('expectations', `${switchState.toLowerCase()}.json`);
  const absolute = path.join(outDir, relative);
  fs.mkdirSync(path.dirname(absolute), { recursive: true });
  const bytes = Buffer.from(`${JSON.stringify(body, null, 2)}\n`, 'utf8');
  fs.writeFileSync(absolute, bytes);
  return { relative, digest: sha256(bytes) };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  const payloadBytes = fs.readFileSync(path.resolve(args.payload));
  const payload = JSON.parse(payloadBytes.toString('utf8'));
  const hearingId = args.hearingId
    || (payload && payload.hearing && payload.hearing.id)
    || 'UNKNOWN';

  const legacyRepo = resolveLegacyRepo(args.legacyRepo);
  const provenance = readRuntimeProvenance(legacyRepo);
  // eslint-disable-next-line global-require, import/no-dynamic-require
  const { probationFilter } = require(path.join(legacyRepo, LEGACY_FILTER_RELATIVE_PATH));
  if (typeof probationFilter !== 'function') {
    fail('the legacy module did not export probationFilter');
  }

  const states = args.switch === 'BOTH' ? ['ACTIVE', 'INACTIVE'] : [args.switch];
  const outDir = path.resolve(args.outDir);
  fs.mkdirSync(outDir, { recursive: true });

  const expectations = [];
  for (const switchState of states) {
    // eslint-disable-next-line no-await-in-loop
    const outcome = await runOne(probationFilter, payload, switchState, hearingId);
    const entry = {
      switchState,
      form: args.form,
      source: 'EXECUTED_LEGACY',
      result: outcome.result,
    };
    if (outcome.result === 'BODY') {
      const written = writeBody(outDir, switchState, outcome.body);
      entry.bodyFile = written.relative;
      entry.bodyDigestSha256 = written.digest;
    } else {
      entry.noMessageKind = outcome.noMessageKind;
      if (outcome.result === 'RULES_FAILURE') {
        entry.thrownErrorName = outcome.thrownErrorName;
        entry.thrownErrorMessage = outcome.thrownErrorMessage;
        entry.thrownInFunction = outcome.thrownInFunction;
        entry.ruleId = null; // the author sets the rule id from thrownInFunction; see §5.1.
      }
    }
    expectations.push(entry);
  }

  const summary = {
    generatedBy: 'tools/oracle-harness/run-oracle.js',
    form: args.form,
    hearingIdPresent: hearingId !== 'UNKNOWN',
    provenance,
    expectations,
  };
  const summaryBytes = Buffer.from(`${JSON.stringify(summary, null, 2)}\n`, 'utf8');
  fs.writeFileSync(path.join(outDir, 'oracle.json'), summaryBytes);

  // stdout: value-free only. No payload, no body, no expectation content.
  process.stdout.write(`oracle-harness: recorded against ${provenance.legacyCommit} on ${provenance.nodeVersion}\n`);
  for (const entry of expectations) {
    const extra = entry.result === 'BODY'
      ? ` body=${entry.bodyFile} sha256=${entry.bodyDigestSha256}`
      : ` kind=${entry.noMessageKind}${entry.thrownInFunction ? ` thrownIn=${entry.thrownInFunction}` : ''}`;
    process.stdout.write(`  ${entry.switchState}/${entry.form}: ${entry.result}${extra}\n`);
  }
  process.stdout.write(`  summary: ${path.join(args.outDir, 'oracle.json')}\n`);
}

// Run as a CLI only when invoked directly; stay side-effect-free when required by a test.
if (require.main === module) {
  main().catch((err) => {
    fail(err && err.stack ? err.stack : String(err));
  });
}

module.exports = {
  parseArgs,
  wasEmptiedByR1,
  firstLegacyFrame,
  runOne,
  sha256,
  writeBody,
  resolveLegacyRepo,
  readRuntimeProvenance,
  main,
};
