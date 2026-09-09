import { ApiError, currentUser, request } from './api.js';
import { loginUrl, loginReturnUrl } from './navigation.js';
import { guardPasswordTransfer, passwordError } from './password-policy.js';

const $ = id => document.getElementById(id);
const returnTo = loginReturnUrl(new URLSearchParams(location.search).get('returnTo'));
const fields = ['email', 'nickname', 'password', 'password-confirm'];
const passwordFields = ['password', 'password-confirm'];
let busy = false;
let generation = 0;
let mutationController;

function showError(message = '') {
  $('signup-error').textContent = message;
  $('signup-error').hidden = !message;
}

function fieldError(id, message = '') {
  $(id).setAttribute('aria-invalid', String(Boolean(message)));
  $(`${id}-error`).textContent = message;
  $(`${id}-error`).hidden = !message;
}

function setPasswordVisibility(id, reveal) {
  const label = id === 'password' ? '비밀번호' : '비밀번호 확인';
  $(id).type = reveal ? 'text' : 'password';
  $(`toggle-${id}`).textContent = reveal ? '숨기기' : '보기';
  $(`toggle-${id}`).setAttribute('aria-label', `${label} ${reveal ? '숨기기' : '보기'}`);
  $(`toggle-${id}`).setAttribute('aria-pressed', String(reveal));
}

function clearPasswords() {
  for (const id of passwordFields) {
    $(id).value = '';
    setPasswordVisibility(id, false);
  }
}

function setBusy(value) {
  busy = value;
  $('signup-fields').disabled = value;
  $('switch-account').disabled = value;
  $('signup-form').setAttribute('aria-busy', String(value));
  $('signup-submit').textContent = value ? '가입 중…' : '가입하기';
  $('login-link').setAttribute('aria-disabled', String(value));
  if (value) {
    $('login-link').removeAttribute('href');
    $('login-link').setAttribute('tabindex', '-1');
  } else {
    $('login-link').href = loginUrl(returnTo);
    $('login-link').removeAttribute('tabindex');
  }
}

function renderSession(user) {
  $('session-status').hidden = true;
  $('signup-panel').hidden = Boolean(user);
  $('signed-in-panel').hidden = !user;
  $('signed-in-name').textContent = user?.nickname ?? '';
  if (user) clearPasswords();
}

async function checkSession() {
  if (busy) return;
  const attempt = ++generation;
  try {
    const user = await currentUser();
    if (attempt !== generation) return;
    renderSession(user);
  } catch {
    if (attempt !== generation) return;
    $('session-status').textContent = '로그인 상태를 확인하지 못했어요. 연결 상태를 확인한 뒤 가입해 주세요.';
    $('session-status').hidden = false;
  }
}

async function signup(event) {
  event.preventDefault();
  if (busy) return;
  showError();
  for (const id of fields) fieldError(id);
  const email = $('email').value.trim();
  const nickname = $('nickname').value.trim();
  const password = $('password').value;
  const confirmation = $('password-confirm').value;
  $('email').value = email;
  $('nickname').value = nickname;
  let invalid;
  const reject = (id, message) => { fieldError(id, message); invalid ??= $(id); };
  if (!email || email.length > 100 || $('email').validity.typeMismatch) {
    reject('email', email ? '올바른 이메일 주소를 입력해 주세요. (최대 100자)' : '이메일을 입력해 주세요.');
  }
  if (nickname.length < 2 || nickname.length > 100) {
    reject('nickname', '닉네임을 2~100자로 입력해 주세요.');
  }
  const passwordMessage = passwordError(password, 4);
  if (passwordMessage) reject('password', passwordMessage);
  if (!confirmation) {
    reject('password-confirm', '비밀번호를 한 번 더 입력해 주세요.');
  } else if (passwordError(confirmation)) {
    reject('password-confirm', passwordError(confirmation));
  } else if (password !== confirmation) {
    reject('password-confirm', '비밀번호가 일치하지 않아요. 다시 확인해 주세요.');
  }
  if (invalid) { invalid.focus(); return; }

  const attempt = ++generation;
  mutationController = new AbortController();
  setBusy(true);
  for (const id of passwordFields) setPasswordVisibility(id, false);
  $('session-status').hidden = true;
  try {
    // Signup creates an account only. The user signs in on the login page afterwards.
    await request('/api/auth/signup', { method: 'POST', body: { email, nickname, password }, signal: mutationController.signal });
    if (attempt !== generation) return;
    clearPasswords();
    location.replace(`${loginUrl(returnTo)}&registered=1`);
  } catch (error) {
    if (attempt !== generation) return;
    let message = error.message || '가입하지 못했어요. 잠시 후 다시 시도해 주세요.';
    if (error instanceof ApiError && error.status === 403) {
      message = '가입 요청을 확인하지 못했어요. 가입하기 버튼을 다시 눌러 주세요.';
    } else if (error instanceof ApiError && error.status === 0) {
      message += ' 가입이 완료되었을 수도 있으니 로그인 화면에서 먼저 확인해 주세요.';
    }
    showError(message);
  } finally {
    if (attempt === generation) {
      mutationController = undefined;
      setBusy(false);
    }
  }
}

async function switchAccount() {
  if (busy) return;
  const attempt = ++generation;
  mutationController = new AbortController();
  setBusy(true);
  showError();
  $('switch-account').textContent = '로그아웃 중…';
  try {
    await request('/api/auth/logout', { method: 'POST', signal: mutationController.signal });
    if (attempt !== generation) return;
    renderSession(null);
    for (const id of fields) fieldError(id);
  } catch (error) {
    if (attempt === generation) showError(error.message);
  } finally {
    if (attempt === generation) {
      mutationController = undefined;
      setBusy(false);
      $('switch-account').textContent = '로그아웃하고 새 계정 만들기';
      if (!$('signup-panel').hidden) $('email').focus();
    }
  }
}

$('back-link').href = returnTo;
$('continue-link').href = returnTo;
$('login-link').href = loginUrl(returnTo);
$('login-link').addEventListener('click', event => { if (busy) event.preventDefault(); });
$('signup-form').addEventListener('submit', signup);
$('switch-account').addEventListener('click', switchAccount);
for (const id of fields) {
  $(id).addEventListener('input', () => {
    fieldError(id, passwordFields.includes(id) && $(id).value ? passwordError($(id).value) : '');
    if (id === 'password') fieldError('password-confirm');
    showError();
  });
}
for (const id of passwordFields) {
  guardPasswordTransfer($(id), message => { showError(); fieldError(id, message); });
  $(`toggle-${id}`).addEventListener('click', () => setPasswordVisibility(id, $(id).type === 'password'));
}
window.addEventListener('pagehide', () => {
  ++generation;
  mutationController?.abort();
  mutationController = undefined;
  clearPasswords();
  setBusy(false);
  $('switch-account').textContent = '로그아웃하고 새 계정 만들기';
});
window.addEventListener('pageshow', event => { if (event.persisted) checkSession(); });
setBusy(false);
checkSession();
