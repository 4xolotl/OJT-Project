import { ApiError, currentUser, request } from './api.js';
import { safeReturnUrl, signupUrl } from './navigation.js';
import { guardPasswordTransfer, passwordError } from './password-policy.js';

const $ = id => document.getElementById(id);
const params = new URLSearchParams(location.search);
const returnTo = safeReturnUrl(params.get('returnTo'));
const googleAuthorizationUrl = '/oauth2/authorization/google';
const oauthMessages = Object.freeze({
  cancelled: 'Google 로그인을 취소했어요. 다시 시도하거나 이메일로 로그인해 주세요.',
  email_conflict: '같은 이메일로 가입한 계정이 있어요. 기존 이메일과 비밀번호로 로그인해 주세요. 계정은 자동으로 연결하지 않아요.',
  invalid_identity: 'Google 계정 정보를 확인하지 못했어요. 다른 계정을 선택하거나 이메일로 로그인해 주세요.',
  failed: 'Google 로그인에 실패했어요. 다시 시도하거나 이메일로 로그인해 주세요.'
});
let busy = false;
let generation = 0;
let mutationController;
let providerState = 'loading';
let providerGeneration = 0;
let providerController;
let signedIn = false;
let googleNavigating = false;

function renderProvider() {
  $('google-login').disabled = busy || signedIn || providerState !== 'enabled';
  $('google-login').textContent = googleNavigating ? 'Google로 이동 중…' : 'Google로 계속하기';
  const messages = {
    loading: 'Google 로그인 연결을 확인하고 있어요.',
    disabled: 'Google 로그인 연결을 준비하고 있어요. 이메일로 로그인할 수 있어요.',
    failed: 'Google 로그인 연결을 확인하지 못했어요. 다시 확인하거나 이메일로 로그인해 주세요.'
  };
  $('google-status').textContent = messages[providerState] ?? '';
  $('google-status').hidden = providerState === 'enabled';
  $('google-retry').hidden = !['disabled', 'failed'].includes(providerState);
  $('google-retry').disabled = busy;
}

async function checkProviders() {
  if (busy || providerController) return;
  const attempt = ++providerGeneration;
  providerController = new AbortController();
  providerState = 'loading';
  renderProvider();
  try {
    const providers = await request('/api/auth/providers', { signal: providerController.signal });
    if (attempt !== providerGeneration) return;
    if (providers?.google?.enabled === false) {
      providerState = 'disabled';
    } else if (providers?.google?.enabled === true && providers.google.authorizationUrl === googleAuthorizationUrl) {
      providerState = 'enabled';
    } else {
      providerState = 'failed';
    }
  } catch {
    if (attempt !== providerGeneration) return;
    providerState = 'failed';
  } finally {
    if (attempt === providerGeneration) {
      providerController = undefined;
      renderProvider();
    }
  }
}

function googleLogin() {
  if (busy || signedIn || providerState !== 'enabled') return;
  ++generation;
  showError();
  $('password').value = '';
  hidePassword();
  fieldError('password');
  googleNavigating = true;
  setBusy(true);
  try {
    location.assign(`${googleAuthorizationUrl}?returnTo=${encodeURIComponent(returnTo)}`);
  } catch {
    googleNavigating = false;
    setBusy(false);
    showError(oauthMessages.failed);
  }
}

function showError(message = '') {
  $('login-error').textContent = message;
  $('login-error').hidden = !message;
}

function fieldError(id, message = '') {
  $(id).setAttribute('aria-invalid', String(Boolean(message)));
  $(`${id}-error`).textContent = message;
  $(`${id}-error`).hidden = !message;
}

function hidePassword() {
  $('password').type = 'password';
  $('toggle-password').textContent = '보기';
  $('toggle-password').setAttribute('aria-label', '비밀번호 보기');
  $('toggle-password').setAttribute('aria-pressed', 'false');
}

function setBusy(value) {
  busy = value;
  $('login-fields').disabled = value;
  $('switch-account').disabled = value;
  $('login-form').setAttribute('aria-busy', String(value));
  $('login-submit').textContent = value && !googleNavigating ? '로그인 중…' : '로그인';
  $('signup-link').setAttribute('aria-disabled', String(value));
  if (value) {
    $('signup-link').removeAttribute('href');
    $('signup-link').setAttribute('tabindex', '-1');
  } else {
    $('signup-link').href = signupUrl(returnTo);
    $('signup-link').removeAttribute('tabindex');
  }
  renderProvider();
}

function renderSession(user) {
  signedIn = Boolean(user);
  $('session-status').hidden = true;
  $('login-panel').hidden = Boolean(user);
  $('signed-in-panel').hidden = !user;
  $('signed-in-name').textContent = user?.nickname ?? '';
  if (user) {
    $('password').value = '';
    hidePassword();
  }
  renderProvider();
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
    $('session-status').textContent = '로그인 상태를 확인하지 못했어요. 직접 로그인하거나 잠시 후 다시 방문해 주세요.';
    $('session-status').hidden = false;
  }
}

async function login(event) {
  event.preventDefault();
  if (busy) return;
  showError();
  fieldError('email');
  fieldError('password');
  const email = $('email').value.trim();
  const password = $('password').value;
  $('email').value = email;
  let invalid;
  if (!email || email.length > 100 || $('email').validity.typeMismatch) {
    fieldError('email', email ? '올바른 이메일 주소를 입력해 주세요. (최대 100자)' : '이메일을 입력해 주세요.');
    invalid = $('email');
  }
  const passwordMessage = passwordError(password);
  if (passwordMessage) {
    fieldError('password', passwordMessage);
    invalid ??= $('password');
  }
  if (invalid) {
    invalid.focus();
    return;
  }

  const attempt = ++generation;
  mutationController = new AbortController();
  setBusy(true);
  hidePassword();
  $('session-status').hidden = true;
  try {
    await request('/api/auth/login', { method: 'POST', body: { email, password }, signal: mutationController.signal });
    if (attempt !== generation) return;
    $('password').value = '';
    location.replace(returnTo);
  } catch (error) {
    if (attempt !== generation) return;
    const message = error instanceof ApiError && error.status === 401
      ? '이메일 또는 비밀번호를 확인해 주세요.'
      : error instanceof ApiError && error.status === 403
        ? '로그인 요청을 확인하지 못했어요. 로그인 버튼을 다시 눌러 주세요.'
        : error.message;
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
    fieldError('email');
    fieldError('password');
  } catch (error) {
    if (attempt === generation) showError(error.message);
  } finally {
    if (attempt === generation) {
      mutationController = undefined;
      setBusy(false);
      $('switch-account').textContent = '로그아웃하고 다른 계정으로 로그인';
      if (!$('login-panel').hidden) $('email').focus();
    }
  }
}

$('registration-notice').hidden = params.get('registered') !== '1';
if (params.has('oauthError')) {
  const error = params.get('oauthError');
  showError(Object.hasOwn(oauthMessages, error) ? oauthMessages[error] : oauthMessages.failed);
}
$('back-link').href = returnTo;
$('continue-link').href = returnTo;
$('signup-link').addEventListener('click', event => { if (busy) event.preventDefault(); });
$('login-form').addEventListener('submit', login);
$('switch-account').addEventListener('click', switchAccount);
$('google-login').addEventListener('click', googleLogin);
$('google-retry').addEventListener('click', checkProviders);
for (const id of ['email', 'password']) {
  $(id).addEventListener('input', () => {
    fieldError(id, id === 'password' && $(id).value ? passwordError($(id).value) : '');
    showError();
  });
}
guardPasswordTransfer($('password'), message => { showError(); fieldError('password', message); });
$('toggle-password').addEventListener('click', () => {
  const reveal = $('password').type === 'password';
  $('password').type = reveal ? 'text' : 'password';
  $('toggle-password').textContent = reveal ? '숨기기' : '보기';
  $('toggle-password').setAttribute('aria-label', reveal ? '비밀번호 숨기기' : '비밀번호 보기');
  $('toggle-password').setAttribute('aria-pressed', String(reveal));
});
window.addEventListener('pagehide', () => {
  ++generation;
  mutationController?.abort();
  mutationController = undefined;
  ++providerGeneration;
  providerController?.abort();
  providerController = undefined;
  googleNavigating = false;
  $('password').value = '';
  hidePassword();
  setBusy(false);
  $('switch-account').textContent = '로그아웃하고 다른 계정으로 로그인';
});
window.addEventListener('pageshow', event => {
  if (event.persisted) {
    checkSession();
    checkProviders();
  }
});
setBusy(false);
checkSession();
checkProviders();
