'use strict';

/*
 * Harness self-tests (node --test). These stay outside the Gradle build — the oracle is
 * JavaScript — but guard the classification, argument-parsing, body-writing, provenance and CLI
 * entry-point logic that produces every ACTIVE / synthetic corpus expectation (FR-001-022/023).
 * They inject a fake probationFilter and stub fs / childProcess / process.argv, so no legacy
 * checkout is required to run them.
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const childProcess = require('node:child_process');

const {
  parseArgs,
  wasEmptiedByR1,
  firstLegacyFrame,
  runOne,
  sha256,
  writeBody,
  resolveLegacyRepo,
  readRuntimeProvenance,
  main,
} = require('../run-oracle.js');

const REQUIRED = ['--payload', 'p.json', '--out-dir', 'out'];

// Run fn with process.exit and process.stderr.write stubbed, so a call to the harness's fail()
// surfaces as a captured exit code plus stderr rather than killing the whole test process.
function captureExit(fn) {
  const originalExit = process.exit;
  const originalWrite = process.stderr.write;
  const exitCodes = [];
  let stderr = '';
  process.exit = (code) => { exitCodes.push(code); throw new Error(`process.exit(${code})`); };
  process.stderr.write = (chunk) => { stderr += chunk; return true; };
  let result;
  try {
    result = fn();
  } catch (err) {
    // expected when fail() unwinds via the stubbed exit
  } finally {
    process.exit = originalExit;
    process.stderr.write = originalWrite;
  }
  return { result, exitCodes, stderr };
}

// Same, but for an async fn (main()) whose rejection must be swallowed the same way.
async function captureExitAsync(fn) {
  const originalExit = process.exit;
  const originalWrite = process.stderr.write;
  const exitCodes = [];
  let stderr = '';
  process.exit = (code) => { exitCodes.push(code); throw new Error(`process.exit(${code})`); };
  process.stderr.write = (chunk) => { stderr += chunk; return true; };
  try {
    await fn();
  } catch (err) {
    // expected when fail() unwinds via the stubbed exit
  } finally {
    process.exit = originalExit;
    process.stderr.write = originalWrite;
  }
  return { exitCodes, stderr };
}

function stubGit({ commit = '122e2152deadbeef', dirty = false, npm = '10.1.0' } = {}) {
  const original = childProcess.execFileSync;
  childProcess.execFileSync = (cmd, cmdArgs) => {
    if (cmd === 'git' && cmdArgs.includes('rev-parse')) return `${commit}\n`;
    if (cmd === 'git' && cmdArgs.includes('status')) return dirty ? ' M some/file\n' : '\n';
    if (cmd === 'npm') {
      if (npm === null) throw new Error('npm: command not found');
      return `${npm}\n`;
    }
    throw new Error(`unexpected exec: ${cmd}`);
  };
  return () => { childProcess.execFileSync = original; };
}

function writeLegacyFixture(root, filterBody) {
  const filterDir = path.join(
    root, 'azure-functions', 'durable-functions', 'ProbationHearingResultedFilter',
  );
  fs.mkdirSync(filterDir, { recursive: true });
  fs.writeFileSync(path.join(filterDir, 'functions.js'), filterBody);
}

test('parseArgs defaults --switch to BOTH when the flag is absent', () => {
  const { result, exitCodes, stderr } = captureExit(() => parseArgs([...REQUIRED]));
  assert.deepEqual(exitCodes, [], `parseArgs exited instead of defaulting to BOTH: ${stderr}`);
  assert.equal(result.switch, 'BOTH');
  assert.equal(result.form, 'CACHE');
});

test('parseArgs accepts an explicit --switch in any case', () => {
  for (const [given, expected] of [
    ['ACTIVE', 'ACTIVE'],
    ['inactive', 'INACTIVE'],
    ['both', 'BOTH'],
    ['BOTH', 'BOTH'],
  ]) {
    const args = parseArgs([...REQUIRED, '--switch', given]);
    assert.equal(args.switch, expected);
  }
});

test('parseArgs rejects an unknown --switch value with exit code 1', () => {
  const { exitCodes, stderr } = captureExit(() => parseArgs([...REQUIRED, '--switch', 'maybe']));
  assert.deepEqual(exitCodes, [1]);
  assert.match(stderr, /--switch must be ACTIVE, INACTIVE or BOTH/);
});

test('parseArgs rejects an invalid --form value with exit code 1', () => {
  const { exitCodes, stderr } = captureExit(() => parseArgs([...REQUIRED, '--form', 'disk']));
  assert.deepEqual(exitCodes, [1]);
  assert.match(stderr, /--form must be CACHE or FALLBACK/);
});

test('parseArgs requires --payload', () => {
  const { exitCodes, stderr } = captureExit(() => parseArgs(['--out-dir', 'out']));
  assert.deepEqual(exitCodes, [1]);
  assert.match(stderr, /--payload is required/);
});

test('parseArgs requires --out-dir', () => {
  const { exitCodes, stderr } = captureExit(() => parseArgs(['--payload', 'p.json']));
  assert.deepEqual(exitCodes, [1]);
  assert.match(stderr, /--out-dir is required/);
});

test('parseArgs rejects a flag given with no following value', () => {
  const { exitCodes, stderr } = captureExit(() => parseArgs(['--payload']));
  assert.deepEqual(exitCodes, [1]);
  assert.match(stderr, /missing value for --payload/);
});

test('parseArgs rejects an unknown argument', () => {
  const { exitCodes, stderr } = captureExit(() => parseArgs([...REQUIRED, '--nope']));
  assert.deepEqual(exitCodes, [1]);
  assert.match(stderr, /unknown argument: --nope/);
});

test('parseArgs --help prints usage and exits 0 without requiring --payload/--out-dir', () => {
  const originalExit = process.exit;
  const originalWrite = process.stdout.write;
  const exitCodes = [];
  let stdout = '';
  process.exit = (code) => { exitCodes.push(code); throw new Error(`process.exit(${code})`); };
  process.stdout.write = (chunk) => { stdout += chunk; return true; };
  try {
    parseArgs(['--help']);
  } catch (err) {
    // expected: the stubbed exit unwinds via a thrown error
  } finally {
    process.exit = originalExit;
    process.stdout.write = originalWrite;
  }
  assert.deepEqual(exitCodes, [0]);
  assert.match(stdout, /USAGE/);
});

test('wasEmptiedByR1 mirrors the box-hearing truthiness flags', () => {
  assert.equal(wasEmptiedByR1({ hearing: { isSJPHearing: true } }), true);
  assert.equal(wasEmptiedByR1({ hearing: { isBoxHearing: true } }), true);
  assert.equal(wasEmptiedByR1({ hearing: { isVirtualBoxHearing: true } }), true);
  assert.equal(wasEmptiedByR1({ hearing: { isSJPHearing: false } }), false);
  assert.equal(wasEmptiedByR1({ hearing: {} }), false);
  assert.equal(wasEmptiedByR1({}), false);
  assert.equal(wasEmptiedByR1(null), false);
});

test('firstLegacyFrame extracts the first functions.js frame name', () => {
  const stack = [
    'TypeError: boom',
    '    at Object.<anonymous> (/x/other.js:1:1)',
    '    at filterSJPCaseWhichAreResultedInSJPSessionAndBoxHearing (/x/functions.js:42:7)',
    '    at probationFilter (/x/functions.js:10:3)',
  ].join('\n');
  assert.equal(firstLegacyFrame(stack), 'filterSJPCaseWhichAreResultedInSJPSessionAndBoxHearing');
  assert.equal(firstLegacyFrame(null), null);
  assert.equal(firstLegacyFrame('no frames here'), null);
});

test('runOne classifies a produced body as BODY', async () => {
  const filter = async () => ({ hearing: { id: 'H1' } });
  const outcome = await runOne(filter, { hearing: { id: 'H1' } }, 'INACTIVE', 'H1');
  assert.equal(outcome.result, 'BODY');
  assert.deepEqual(outcome.body, { hearing: { id: 'H1' } });
});

test('runOne attributes an empty result to R1 when a box flag is set', async () => {
  const filter = async () => ({});
  const outcome = await runOne(filter, { hearing: { isBoxHearing: true } }, 'ACTIVE', 'H1');
  assert.equal(outcome.result, 'NO_MESSAGE');
  assert.equal(outcome.noMessageKind, 'EMPTIED_BY_R1');
});

test('runOne attributes an empty result to R11 when no box flag is set', async () => {
  const filter = async () => ({});
  const outcome = await runOne(filter, { hearing: { isSJPHearing: false } }, 'ACTIVE', 'H1');
  assert.equal(outcome.result, 'NO_MESSAGE');
  assert.equal(outcome.noMessageKind, 'EMPTIED_BY_R11');
});

test('runOne records a thrown rule failure with its legacy frame', async () => {
  const filter = async () => {
    const err = new TypeError('Cannot read properties of undefined');
    err.stack = [
      'TypeError: Cannot read properties of undefined',
      '    at filterSJPCaseWhichAreResultedInSJPSessionAndBoxHearing (/x/functions.js:42:7)',
    ].join('\n');
    throw err;
  };
  const outcome = await runOne(filter, { hearing: {} }, 'ACTIVE', 'H1');
  assert.equal(outcome.result, 'RULES_FAILURE');
  assert.equal(outcome.noMessageKind, 'RULES_FAILURE');
  assert.equal(outcome.thrownErrorName, 'TypeError');
  assert.equal(outcome.thrownInFunction, 'filterSJPCaseWhichAreResultedInSJPSessionAndBoxHearing');
});

test('sha256 hashes bytes deterministically', () => {
  assert.equal(
    sha256(Buffer.from('abc', 'utf8')),
    'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
  );
});

test('writeBody writes pretty JSON plus a trailing newline and returns its digest', () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'oracle-writebody-'));
  try {
    const written = writeBody(tmpDir, 'ACTIVE', { b: 2, a: 1 });
    assert.equal(written.relative, path.join('expectations', 'active.json'));
    const absolute = path.join(tmpDir, written.relative);
    const bytes = fs.readFileSync(absolute);
    assert.equal(bytes.toString('utf8'), `${JSON.stringify({ b: 2, a: 1 }, null, 2)}\n`);
    assert.equal(written.digest, sha256(bytes));
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('resolveLegacyRepo returns an explicit repo when the filter file exists', () => {
  const originalExists = fs.existsSync;
  const seen = [];
  fs.existsSync = (p) => { seen.push(p); return true; };
  try {
    const repo = resolveLegacyRepo('/some/legacy/repo');
    assert.equal(repo, path.resolve('/some/legacy/repo'));
    assert.match(seen[0], /functions\.js$/);
  } finally {
    fs.existsSync = originalExists;
  }
});

test('resolveLegacyRepo exits when no candidate holds the filter file', () => {
  const originalExists = fs.existsSync;
  fs.existsSync = () => false;
  try {
    const { exitCodes, stderr } = captureExit(() => resolveLegacyRepo('/missing/repo'));
    assert.deepEqual(exitCodes, [1]);
    assert.match(stderr, /could not find the legacy filter/);
  } finally {
    fs.existsSync = originalExists;
  }
});

test('readRuntimeProvenance records the pinned commit, clean tree and runtime', () => {
  const restore = stubGit();
  try {
    const provenance = readRuntimeProvenance('/legacy');
    assert.equal(provenance.source, 'EXECUTED_LEGACY');
    assert.equal(provenance.legacyCommit, '122e2152deadbeef');
    assert.equal(provenance.legacyTreeClean, true);
    assert.equal(provenance.npmVersion, '10.1.0');
    assert.equal(provenance.nodeVersion, process.version);
    assert.equal(provenance.npmVersionNote, undefined);
  } finally {
    restore();
  }
});

test('readRuntimeProvenance refuses a dirty legacy tree', () => {
  const restore = stubGit({ dirty: true });
  try {
    const { exitCodes, stderr } = captureExit(() => readRuntimeProvenance('/legacy'));
    assert.deepEqual(exitCodes, [1]);
    assert.match(stderr, /uncommitted changes/);
  } finally {
    restore();
  }
});

test('readRuntimeProvenance exits when the legacy repo is not at the pinned commit', () => {
  const restore = stubGit({ commit: 'feedface' });
  try {
    const { exitCodes, stderr } = captureExit(() => readRuntimeProvenance('/legacy'));
    assert.deepEqual(exitCodes, [1]);
    assert.match(stderr, /not the pinned oracle/);
  } finally {
    restore();
  }
});

test('readRuntimeProvenance records a null npm version when npm is not resolvable', () => {
  const restore = stubGit({ npm: null });
  try {
    const provenance = readRuntimeProvenance('/legacy');
    assert.equal(provenance.npmVersion, null);
    assert.match(provenance.npmVersionNote, /npm not resolvable/);
  } finally {
    restore();
  }
});

test('main() writes expectations and oracle.json without ever echoing the payload or body', async () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'oracle-main-'));
  const MARKER = 'SENTINEL-MUST-NOT-LEAK-7G3';
  try {
    const legacyRepo = path.join(tmpRoot, 'legacy');
    writeLegacyFixture(
      legacyRepo,
      'exports.probationFilter = async function probationFilter(json) { return json; };\n',
    );
    const payloadPath = path.join(tmpRoot, 'payload.json');
    fs.writeFileSync(payloadPath, JSON.stringify({ hearing: { id: 'H-MAIN-TEST' }, marker: MARKER }));
    const outDir = path.join(tmpRoot, 'out');

    const restoreGit = stubGit();
    const originalArgv = process.argv;
    const originalWrite = process.stdout.write;
    let stdout = '';
    process.stdout.write = (chunk) => { stdout += chunk; return true; };
    process.argv = [
      'node', 'run-oracle.js',
      '--payload', payloadPath, '--out-dir', outDir, '--switch', 'ACTIVE', '--legacy-repo', legacyRepo,
    ];
    try {
      await main();
    } finally {
      restoreGit();
      process.stdout.write = originalWrite;
      process.argv = originalArgv;
    }

    assert.ok(!stdout.includes(MARKER), `stdout must never contain a corpus value; got: ${stdout}`);

    const oracle = JSON.parse(fs.readFileSync(path.join(outDir, 'oracle.json'), 'utf8'));
    assert.equal(oracle.hearingIdPresent, true);
    assert.equal(oracle.expectations.length, 1);
    assert.equal(oracle.expectations[0].result, 'BODY');
    assert.equal(oracle.provenance.legacyCommit, '122e2152deadbeef');

    const written = JSON.parse(fs.readFileSync(path.join(outDir, 'expectations', 'active.json'), 'utf8'));
    assert.deepEqual(written, { hearing: { id: 'H-MAIN-TEST' }, marker: MARKER });
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test('main() derives hearingId from the payload when --hearing-id is omitted', async () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'oracle-main-hid-'));
  try {
    const legacyRepo = path.join(tmpRoot, 'legacy');
    writeLegacyFixture(legacyRepo, 'exports.probationFilter = async function probationFilter() { return {}; };\n');
    const payloadPath = path.join(tmpRoot, 'payload.json');
    fs.writeFileSync(payloadPath, JSON.stringify({ hearing: { id: 'H-FROM-PAYLOAD' } }));
    const outDir = path.join(tmpRoot, 'out');

    const restoreGit = stubGit();
    const originalArgv = process.argv;
    const originalWrite = process.stdout.write;
    process.stdout.write = () => true;
    process.argv = [
      'node', 'run-oracle.js', '--payload', payloadPath, '--out-dir', outDir, '--switch', 'ACTIVE',
      '--legacy-repo', legacyRepo,
    ];
    try {
      await main();
    } finally {
      restoreGit();
      process.stdout.write = originalWrite;
      process.argv = originalArgv;
    }

    const oracle = JSON.parse(fs.readFileSync(path.join(outDir, 'oracle.json'), 'utf8'));
    assert.equal(oracle.hearingIdPresent, true);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test('main() fails when the legacy module does not export probationFilter', async () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'oracle-main-badexport-'));
  try {
    const legacyRepo = path.join(tmpRoot, 'legacy');
    writeLegacyFixture(legacyRepo, 'exports.probationFilter = 42;\n');
    const payloadPath = path.join(tmpRoot, 'payload.json');
    fs.writeFileSync(payloadPath, JSON.stringify({ hearing: { id: 'H1' } }));
    const outDir = path.join(tmpRoot, 'out');

    const restoreGit = stubGit();
    const originalArgv = process.argv;
    process.argv = [
      'node', 'run-oracle.js', '--payload', payloadPath, '--out-dir', outDir, '--legacy-repo', legacyRepo,
    ];
    let outcome;
    try {
      outcome = await captureExitAsync(main);
    } finally {
      restoreGit();
      process.argv = originalArgv;
    }
    assert.deepEqual(outcome.exitCodes, [1]);
    assert.match(outcome.stderr, /did not export probationFilter/);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});
