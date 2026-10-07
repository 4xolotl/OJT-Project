// Run: node --experimental-vm-modules src/test/js/password-policy.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createContext, SourceTextModule } from 'node:vm';

const policyUrl = new URL('../../main/resources/static/assets/js/password-policy.js', import.meta.url);
const policy = new SourceTextModule(await readFile(policyUrl, 'utf8'), { context: createContext({}) });
await policy.link(specifier => { assert.fail(`Password policy unexpectedly imports ${specifier}`); });
await policy.evaluate();
const {
  SIGNUP_PASSWORD_MIN_LENGTH,
  passwordError,
  signupPasswordError,
  guardPasswordTransfer
} = policy.namespace;
const printableAscii = Array.from({ length: 95 }, (_, index) => String.fromCharCode(index + 32));
const symbols = printableAscii.filter(character => !/[A-Za-z0-9 ]/.test(character));
const checks = [];
function check(name, run) { run(); checks.push(name); }
function rejects(password, minimumLength = 1) {
  const error = passwordError(password, minimumLength);
  assert.equal(typeof error, 'string');
  assert.ok(/[가-힣]/.test(error), 'Invalid passwords need a readable Korean error');
}

check('Every printable ASCII character, including space and all 32 symbols, is allowed in a password', () => {
  assert.equal(symbols.length, 32);
  for (const character of printableAscii) {
    assert.equal(passwordError('Violet' + character), '', `Login rejected ASCII code ${character.charCodeAt(0)}`);
    assert.equal(
      signupPasswordError('VioletRiverMeadow' + character, 'tester@example.com'), '',
      `Signup rejected ASCII code ${character.charCodeAt(0)}`
    );
  }
});

check('No lowercase, uppercase, digit or symbol combination is required', () => {
  for (const password of ['onlylowercasephrase', 'ONLYUPPERCASEPHRASE', '204938576102938', '!^>_&{?~#)<[$]+']) {
    assert.equal(signupPasswordError(password, 'tester@example.com'), '');
  }
});

check('Login accepts the exact original value from 1 to 72 characters', () => {
  rejects('');
  rejects(' '.repeat(15));
  assert.equal(passwordError('!'), '');
  assert.equal(passwordError('  exact spaces  '), '');
  assert.equal(passwordError('A'.repeat(72)), '');
  rejects('A'.repeat(73));
});

check('Signup accepts 15-72 characters and rejects obvious weak values', () => {
  assert.equal(SIGNUP_PASSWORD_MIN_LENGTH, 15);
  assert.ok(signupPasswordError('violet-river-8', 'tester@example.com'));
  assert.equal(signupPasswordError('violet-river-82', 'tester@example.com'), '');
  assert.equal(
    signupPasswordError(
      'R7!violet-river_2026/candle?meadow#orbit=Lake9%forest&cloud2*harborTrail',
      'tester@example.com'
    ),
    ''
  );
  assert.ok(signupPasswordError('A'.repeat(73), 'tester@example.com'));
  for (const weak of [
    'password1234567',
    'P@ssw0rd2026!!!!',
    'P@ssw0rdP@ssw0rd',
    'P@ssw0rdAa1!2026',
    'passwordxpasswordx',
    'correct horse battery staplecorrect horse battery staple',
    'abcabcabcabcabc',
    'abababababababa',
    '123412341234123',
    'abcabcabcabcabX',
    '123456789012345',
    '12345678901234?',
    'qwertyuiopasdfgh',
    'xqwertyuiopasdfgh',
    'xabababababababa',
    '1qaz2wsx3edc4rfv',
    'asdfghjklasdfgh',
    '!\"#$%&\'()*+,-./',
    '!@#$%^&*()_+{}|',
    'abcdefghij     ',
    '!@#$%^&*()     ',
    'a' + ' '.repeat(14),
    ' '.repeat(7) + 'a' + ' '.repeat(7),
    'abc' + ' '.repeat(12),
    ' '.repeat(5) + 'abcde' + ' '.repeat(5),
    'a1b2c3' + ' '.repeat(9),
    'x' + ' '.repeat(13) + 'y',
    'ab' + ' '.repeat(11) + 'cd',
    'a1b' + ' '.repeat(9) + 'c2d',
    ' '.repeat(4) + 'aZ7!mQ2?' + ' '.repeat(4),
    'aZ7!' + ' '.repeat(8) + 'mQ2?',
    'a    b    c    d',
    'a   x   c   p   e',
    'a  x  c  p  e  z',
    'abcdpasswordxyz',
    'passwordabcdefghi',
    'passwordxpassword',
    'password1password',
    '4xolotlpassword'
  ]) {
    assert.ok(signupPasswordError(weak, 'tester@example.com'), `Expected weak password rejection: ${weak}`);
  }
  assert.ok(signupPasswordError('test202620262026', 'test@example.com'));
  assert.ok(signupPasswordError('johnsmithjohnsmith', 'john.smith@example.com'));
  assert.ok(signupPasswordError('abcdjohndoezxcv', 'john.doe@example.com'));
  assert.ok(signupPasswordError('johndoexjohndoe', 'johndoe@example.com'));
  assert.ok(signupPasswordError('johndoe1johndoe', 'johndoe@example.com'));
  assert.ok(signupPasswordError('johnxsmith2026!', 'john.smith@example.com'));
  assert.ok(signupPasswordError(
    'myverylongemailidentifiermyverylongemailidentifier',
    'myverylongemailidentifier@example.com'
  ));
  assert.ok(signupPasswordError('passwordkXmZvQrNpassword', 'tester@example.com'));
  assert.equal(signupPasswordError('passwordkXmZvQrNspassword', 'tester@example.com'), '');
  const maximumLocalPart = 'r7violet9candle2meadow4orbit6lake8xy';
  assert.equal(maximumLocalPart.length, 36);
  assert.ok(signupPasswordError(maximumLocalPart.repeat(2), `${maximumLocalPart}@example.com`));
  assert.equal(signupPasswordError('BlueTestCoffeeTrail', 'test@example.com'), '');
});

check('Signup keeps non-dominant outer spaces and internal passphrase spaces', () => {
  for (const password of [
    ' '.repeat(8) + 'violet river 829',
    'violet river 829' + ' '.repeat(8),
    'violet        river',
    ' '.repeat(5) + 'violet river 829' + ' '.repeat(5),
    ' '.repeat(3) + 'aZ7!mQ2?' + ' '.repeat(4),
    ' '.repeat(4) + 'aZ7!mQ2?x' + ' '.repeat(4),
    'a x c p e z q r t'
  ]) {
    assert.equal(signupPasswordError(password, 'tester@example.com'), '');
  }
});

check('Every ASCII control and DEL are rejected at any position without trimming', () => {
  for (const code of [...Array.from({ length: 32 }, (_, index) => index), 127]) {
    const character = String.fromCharCode(code);
    for (const password of [character + 'Valid123', 'Valid' + character + '123', 'Valid123' + character]) {
      rejects(password);
      assert.ok(signupPasswordError(password, 'tester@example.com'));
    }
  }
  assert.ok(signupPasswordError('VioletRiverMeadow\r\n', 'tester@example.com'));
});

check('Unicode whitespace, invisible marks, fullwidth forms, accents, Hangul and emoji are rejected', () => {
  const forbidden = [
    '\u0085', '\u00a0', '\u200b', '\u200c', '\u200d', '\ufeff', '\u2028', '\u2029', '\u3000',
    'Ａ', '１', '！', '가', 'é', 'e\u0301', '🧪'
  ];
  for (const character of forbidden) {
    rejects('Valid123' + character);
    assert.ok(signupPasswordError('VioletRiverMeadow' + character, 'tester@example.com'));
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

check('Paste and drop reject raw controls, forbidden characters and overlong text without modifying the input', () => {
  const invalid = [
    'Valid123\n', 'Valid123\r', 'Valid123\r\n', 'Valid123\t',
    'Valid123\u00a0', 'Valid123\u200b', 'Valid123가', 'A'.repeat(73)
  ];
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

check('Paste and drop allow valid fragments, spaces, every ASCII symbol, and 72-character transfers', () => {
  for (const type of ['paste', 'drop']) {
    for (const text of ['x', '!', ' '.repeat(15), 'valid fragment', symbols.join(''), 'A'.repeat(72)]) {
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
