export const SIGNUP_PASSWORD_MIN_LENGTH = 15;
const MAX_LENGTH = 72;
const MIN_WEAK_SEQUENCE_LENGTH = 6;
const MIN_DOMINANT_SPACE_PADDING = 8;
const MAX_REPEATED_SEPARATOR_LENGTH = 8;
// This compact set gives immediate feedback for representative weak values.
// The server remains authoritative and checks the full offline blocklist.
const COMMON_PASSWORDS = new Set([
  'admin', 'axolotl', 'board', 'changeme', 'correcthorsebatterystaple', 'iloveyou',
  'letmein', 'monkey', 'ojtboard', 'ojtproject', 'password', 'qwerty', 'secret', 'welcome'
]);
const PREDICTABLE_AFFIXES = new Set([
  'a', 'aa', 'aaa', 'abc', 'abcd', 'abcdef', 'abcdefgh', 'admin', 'asdf',
  'board', 'i', 'ii', 'ojt', 'password', 'qwerty', 'welcome', 'x', 'xx', 'zxcv'
]);
const WEAK_SEQUENCES = [
  '0123456789',
  'abcdefghijklmnopqrstuvwxyz',
  'qwertyuiopasdfghjklzxcvbnm',
  'qwertyuiop',
  'asdfghjkl',
  'zxcvbnm',
  '!\"#$%&\'()*+,-./:;<=>?@[\\]^_`{|}~',
  "`1234567890-=[]\\;',./",
  '1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik9ol0p',
  '1qazxsw23edcvfr45tgbnhy67ujmki89olp0',
  'qazwsxedcrfvtgbyhnujmikolp'
];

// Printable ASCII including space. Reject invalid characters instead of removing,
// trimming or normalizing the password that is sent to the server.
export function passwordError(value, minimumLength = 1) {
  if (typeof value !== 'string' || !value || !value.trim()) return '비밀번호를 입력해 주세요.';
  // A negated character check also rejects a trailing newline; JS $ can match before it.
  if (/[^\x20-\x7e]/.test(value)) {
    return '비밀번호는 출력 가능한 영문, 숫자, 특수문자와 공백만 사용할 수 있어요.';
  }
  if (value.length < minimumLength || value.length > MAX_LENGTH) {
    return minimumLength > 1
      ? `비밀번호를 ${minimumLength}~${MAX_LENGTH}자로 입력해 주세요.`
      : `비밀번호는 ${MAX_LENGTH}자까지 입력할 수 있어요.`;
  }
  return '';
}

export function signupPasswordError(value, email = '') {
  const formatError = passwordError(value, SIGNUP_PASSWORD_MIN_LENGTH);
  if (formatError) return formatError;
  if (isContextSpecific(value, email)) {
    return '이메일 주소나 그 단순 변형은 비밀번호로 사용할 수 없어요.';
  }
  if (isObviouslyGuessable(value)) {
    return '널리 사용되거나 쉽게 추측할 수 있는 비밀번호는 사용할 수 없어요.';
  }
  return '';
}

function passwordTransferError(value) {
  if (/[^\x20-\x7e]/.test(value)) {
    return '비밀번호는 출력 가능한 영문, 숫자, 특수문자와 공백만 사용할 수 있어요.';
  }
  if (value.length > MAX_LENGTH) {
    return `비밀번호는 ${MAX_LENGTH}자까지 입력할 수 있어요.`;
  }
  return '';
}

function isContextSpecific(value, email) {
  if (typeof email !== 'string' || !email.includes('@')) return false;
  const normalizedPassword = comparable(value);
  const undecoratedPassword = comparable(stripPredictableAffixes(value));
  const normalizedEmail = comparable(email);
  const localPart = email.slice(0, email.indexOf('@')).split('+', 1)[0];
  const localCandidates = [localPart, ...localPart.split(/[._-]+/)]
    .map(comparable)
    .filter(candidate => candidate.length >= 4);
  const candidates = new Set([normalizedEmail, ...localCandidates]);
  const repeated = repeatedUnit(normalizedPassword);
  return isBlockedOrPredictableVariant(normalizedPassword, candidates)
    || isBlockedOrPredictableVariant(undecoratedPassword, candidates)
    || (repeated !== null && candidates.has(repeated));
}

function isObviouslyGuessable(value) {
  const normalized = comparable(value);
  const undecorated = comparable(stripPredictableAffixes(value));
  if (isBlockedOrPredictableVariant(normalized, COMMON_PASSWORDS)
      || isBlockedOrPredictableVariant(undecorated, COMMON_PASSWORDS)) return true;
  const pattern = keyboardCanonical(value);
  if (hasDominantSpacePadding(value)
      || hasDominantRepeatedCharacter(value)
      || hasDominantRepeatedCharacter(pattern)) return true;
  const rawWithoutSpaces = value.toLowerCase().replaceAll(' ', '');
  return hasPredictablyDecoratedMatch(pattern, isWeakPattern)
    || hasPredictablyDecoratedMatch(rawWithoutSpaces, isWeakPattern)
    || hasPredictablyDecoratedMatch(normalized, hasRepeatedUnit);
}

function isWeakPattern(value) {
  return hasRepeatedUnit(value) || isWeakWalk(value);
}

function hasDominantSpacePadding(value) {
  let spaces = 0;
  for (const character of value) {
    if (character === ' ') spaces++;
  }
  return spaces >= MIN_DOMINANT_SPACE_PADDING && spaces * 2 >= value.length;
}

function isBlockedOrPredictableVariant(value, blockedValues) {
  return hasPredictablyDecoratedMatch(value, candidate =>
    blockedValues.has(candidate)
      || hasSingleInsertionVariant(candidate, blockedValues)
      || hasBlockedRepeatedSides(candidate, blockedValues));
}

function hasPredictablyDecoratedMatch(value, matcher) {
  if (matcher(value)) return true;
  for (let start = 0; start < value.length; start++) {
    if (start > 0 && !isPredictableDecoration(value.slice(0, start))) continue;
    for (let end = value.length; end > start; end--) {
      if (start === 0 && end === value.length) continue;
      if (end < value.length && !isPredictableDecoration(value.slice(end))) continue;
      if (matcher(value.slice(start, end))) return true;
    }
  }
  return false;
}

function isPredictableDecoration(value) {
  return value.length === 1 || isPredictableAffix(value);
}

function hasSingleInsertionVariant(value, blockedValues) {
  for (let index = 0; index < value.length; index++) {
    if (blockedValues.has(value.slice(0, index) + value.slice(index + 1))) return true;
  }
  return false;
}

function hasBlockedRepeatedSides(value, blockedValues) {
  const maximumSeparatorLength = Math.min(MAX_REPEATED_SEPARATOR_LENGTH, value.length - 2);
  for (let separatorLength = 1; separatorLength <= maximumSeparatorLength; separatorLength++) {
    const candidateCharacters = value.length - separatorLength;
    if (candidateCharacters % 2 !== 0) continue;
    const candidateLength = candidateCharacters / 2;
    const candidate = value.slice(0, candidateLength);
    if (blockedValues.has(candidate)
        && value.slice(candidateLength + separatorLength) === candidate) return true;
  }
  return false;
}

function isPredictableAffix(value) {
  if (PREDICTABLE_AFFIXES.has(value)) return true;
  if (value.length >= 2 && [...value].every(character => character === value[0])) return true;
  for (const sequence of ['0123456789', 'abcdefghijklmnopqrstuvwxyz', 'qwertyuiop', 'asdfghjkl', 'zxcvbnm']) {
    if ((sequence.includes(value) || [...sequence].reverse().join('').includes(value)) && value.length >= 3) return true;
  }
  return false;
}

function comparable(value) {
  const substitutions = { '0': 'o', '1': 'i', '!': 'i', '|': 'i', '3': 'e', '4': 'a', '@': 'a', '5': 's', '$': 's', '7': 't', '+': 't', '8': 'b', '9': 'g' };
  let result = '';
  for (const character of value.toLowerCase()) {
    const folded = substitutions[character] ?? character;
    if (/[a-z0-9]/.test(folded)) result += folded;
  }
  return result;
}

function stripPredictableAffixes(value) {
  return value.replace(/^[^A-Za-z]+|[^A-Za-z]+$/g, '');
}

function keyboardCanonical(value) {
  const shifted = '~!@#$%^&*()_+{}|:\"<>?';
  const base = "`1234567890-=[]\\;',./";
  let result = '';
  for (const original of value.toLowerCase()) {
    const shiftedIndex = shifted.indexOf(original);
    const character = shiftedIndex >= 0 ? base[shiftedIndex] : original;
    if (character >= '!' && character <= '~') result += character;
  }
  return result;
}

function hasDominantRepeatedCharacter(value) {
  let longest = 1;
  let run = 1;
  for (let index = 1; index < value.length; index++) {
    run = value[index] === value[index - 1] ? run + 1 : 1;
    longest = Math.max(longest, run);
  }
  return longest >= 8 && longest * 2 >= value.length;
}

function hasRepeatedUnit(value) {
  const unit = repeatedUnit(value);
  if (unit === null) return false;
  const repetitions = value.length / unit.length;
  return repetitions >= 3 || isBlockedOrPredictableVariant(comparable(unit), COMMON_PASSWORDS);
}

function repeatedUnit(value) {
  for (let size = 1; size <= Math.floor(value.length / 2); size++) {
    const unit = value.slice(0, size);
    let matches = true;
    for (let index = size; index < value.length; index++) {
      if (value[index] !== unit[index % size]) {
        matches = false;
        break;
      }
    }
    if (matches) return unit;
  }
  return null;
}

function isWeakWalk(value) {
  if (value.length < MIN_WEAK_SEQUENCE_LENGTH) return false;
  for (const sequence of WEAK_SEQUENCES) {
    const repeated = sequence.repeat(Math.ceil(value.length / sequence.length) + 1);
    const reversed = [...sequence].reverse().join('').repeat(Math.ceil(value.length / sequence.length) + 1);
    if (repeated.includes(value) || reversed.includes(value)) return true;
  }
  return false;
}

export function guardPasswordTransfer(input, reportError) {
  for (const type of ['paste', 'drop']) {
    input.addEventListener(type, event => {
      // Inspect the transfer before a single-line input can strip CR/LF from it.
      const data = type === 'paste' ? event.clipboardData : event.dataTransfer;
      const text = data?.getData('text/plain');
      if (!text) return;
      const message = passwordTransferError(text);
      if (message) {
        event.preventDefault();
        reportError(message);
      }
    });
  }
}
