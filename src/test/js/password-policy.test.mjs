// Run: node --experimental-vm-modules src/test/js/password-policy.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createContext, SourceTextModule } from 'node:vm';

const policyUrl = new URL('../../main/resources/static/assets/js/password-policy.js', import.meta.url);
const policy = new SourceTextModule(await readFile(policyUrl, 'utf8'), { context: createContext({}) });
await policy.link(specifier => { assert.fail(`Password policy unexpectedly imports ${specifier}`); });
await policy.evaluate();
const { passwordError, guardPasswordTransfer } = policy.namespace;
const printableAscii = Array.from({ length: 94 }, (_, index) => String.fromCharCode(index + 33));
const symbols = printableAscii.filter(character => !/[A-Za-z0-9]/.test(character));
const checks = [];
function check(name, run) { run(); checks.push(name); }
function rejects(password, minimumLength = 1) {
  const error = passwordError(password, minimumLength);
  assert.equal(typeof error, 'string');
  assert.ok(/[가-힣]/.test(error), 'Invalid passwords need a readable Korean error');
}

check('Every printable non-space ASCII character, including all 32 symbols, is allowed', () => {
  assert.equal(symbols.length, 32);
  for (const character of printableAscii) {
    assert.equal(passwordError(character), '', `Login rejected ASCII code ${character.charCodeAt(0)}`);
    assert.equal(passwordError(character.repeat(4), 4), '', `Signup rejected ASCII code ${character.charCodeAt(0)}`);
  }
});

check('No lowercase, uppercase, digit or symbol combination is required', () => {
  for (const password of ['aaaa', 'AAAA', '1234', '!!!!', symbols.join('')]) {
    assert.equal(passwordError(password, 4), '');
  }
});

check('Login defaults to 1-72 characters', () => {
  rejects('');
  assert.equal(passwordError('!'), '');
  assert.equal(passwordError('A'.repeat(72)), '');
  rejects('A'.repeat(73));
});

check('Signup accepts exactly 4-72 characters', () => {
  rejects('', 4);
  rejects('A'.repeat(3), 4);
  assert.equal(passwordError('A'.repeat(4), 4), '');
  assert.equal(passwordError('A'.repeat(72), 4), '');
  rejects('A'.repeat(73), 4);
});

check('Spaces, every ASCII control and DEL are rejected at any position without trimming', () => {
  for (const code of [...Array.from({ length: 33 }, (_, index) => index), 127]) {
    const character = String.fromCharCode(code);
    for (const password of [character + 'Valid123', 'Valid' + character + '123', 'Valid123' + character]) {
      rejects(password);
      rejects(password, 4);
    }
  }
  rejects('Valid123\r\n', 4);
});

check('Unicode whitespace, invisible marks, fullwidth forms, accents, Hangul and emoji are rejected', () => {
  const forbidden = [
    '\u0085', '\u00a0', '\u200b', '\u200c', '\u200d', '\ufeff', '\u2028', '\u2029', '\u3000',
    'Ａ', '１', '！', '가', 'é', 'e\u0301', '🧪'
  ];
  for (const character of forbidden) {
    rejects('Valid123' + character);
    rejects('Valid123' + character, 4);
  }
});

function transferGuard() {
  const listeners = new Map();
  const errors = [];
  const input = { value: 'Existing1!', addEventListener: (type, listener) => listeners.set(type, listener) };
  guardPasswordTransfer(input, message => errors.push(message));
  assert.equal(listeners.size, 2);
  return {
    input, errors,
    transfer(type, text, includeData = true) {
      const formats = [];
      const event = { defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
      if (includeData) {
        const expected = type === 'paste' ? 'clipboardData' : 'dataTransfer';
        const other = type === 'paste' ? 'dataTransfer' : 'clipboardData';
        event[expected] = { getData(format) { formats.push(format); return text; } };
        event[other] = { getData() { assert.fail('Read the wrong transfer data source'); } };
      }
      listeners.get(type)(event);
      if (includeData) assert.deepEqual(formats, ['text/plain']);
      return event;
    }
  };
}

check('Paste and drop reject raw CR/LF, forbidden characters and overlong text without modifying the input', () => {
  const invalid = ['Valid123\n', 'Valid123\r', 'Valid123\r\n', 'Valid123\t', 'Valid 123', 'Valid123\u00a0', 'Valid123\u200b', 'Valid123가', 'A'.repeat(73)];
  for (const type of ['paste', 'drop']) {
    for (const text of invalid) {
      const guard = transferGuard();
      assert.equal(guard.transfer(type, text).defaultPrevented, true);
      assert.equal(guard.input.value, 'Existing1!');
      assert.equal(guard.errors.length, 1);
      assert.equal(guard.errors[0], passwordError(text));
      assert.ok(!guard.errors[0].includes(text));
    }
  }
});

check('Paste and drop allow valid short fragments, every ASCII symbol, and 72-character transfers', () => {
  for (const type of ['paste', 'drop']) {
    for (const text of ['x', '!', symbols.join(''), 'A'.repeat(72)]) {
      const guard = transferGuard();
      assert.equal(guard.transfer(type, text).defaultPrevented, false);
      assert.equal(guard.errors.length, 0);
      assert.equal(guard.input.value, 'Existing1!');
    }
  }
});

check('Empty or unavailable paste/drop data does not block the browser event', () => {
  for (const type of ['paste', 'drop']) {
    const guard = transferGuard();
    assert.equal(guard.transfer(type, '').defaultPrevented, false);
    assert.equal(guard.transfer(type, undefined, false).defaultPrevented, false);
    assert.equal(guard.errors.length, 0);
    assert.equal(guard.input.value, 'Existing1!');
  }
});

export const result = { passed: checks.length, asciiCharacters: printableAscii.length, symbols: symbols.length, checks };
console.log(`password policy: ${checks.length} checks passed (${printableAscii.length} ASCII characters, ${symbols.length} symbols)`);
