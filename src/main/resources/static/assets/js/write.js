import { ApiError, request } from './api.js';
import { loginUrl, signupUrl, safeReturnUrl, writeUrl } from './navigation.js';
import { confirmAction, element, listUrl, notify } from './ui.js';
import { postTextErrors, validateFiles, formatFileSize } from './editor-fields.js';

const $ = id => document.getElementById(id);
const form = $('post-form');
const title = $('post-title');
const content = $('post-content');
const fileInput = $('file-input');
const fields = $('post-fields');
const submit = $('post-submit');
const returnTo = writeUrl();
let files = [];
let user = null;
let busy = false;
let uncertain = false;
let leaving = false;
let sessionRequest;
let mutationRequest;
let submitted = false;

const hasDraft = () => Boolean(title.value || content.value || files.length || uncertain);

function showError(id, message) {
  const node = $(id);
  node.textContent = message;
  node.hidden = !message;
}

function renderState() {
  fields.disabled = busy || uncertain;
  submit.disabled = busy || uncertain || !user;
  submit.textContent = busy ? '처리 중…' : '등록하기';
  form.setAttribute('aria-busy', String(busy));
  $('logout-button').disabled = busy;
  $('refresh-session').disabled = busy || Boolean(sessionRequest);
  $('allow-retry').disabled = busy;
  $('uncertain-panel').hidden = !uncertain;
}

function renderSession(message) {
  $('session-name').hidden = !user;
  $('session-name').textContent = user ? `${user.nickname}님` : '';
  $('logout-button').hidden = !user;
  document.querySelectorAll('.account-actions [data-login-link], .account-actions [data-signup-link]')
    .forEach(link => { link.hidden = Boolean(user); });
  $('guest-notice').hidden = Boolean(user);
  $('session-status').textContent = message ?? (user ? `${user.nickname}님으로 글을 작성하고 있어요.` : '로그인 후 글을 등록할 수 있어요.');
  $('refresh-session').hidden = Boolean(user) && !message;
  renderState();
}

async function refreshSession({ force = false } = {}) {
  if (busy && !force) return false;
  sessionRequest?.abort();
  const controller = new AbortController();
  sessionRequest = controller;
  renderState();
  try {
    let result;
    try { result = await request('/api/auth/me', { signal: controller.signal }); }
    catch (error) {
      if (error instanceof ApiError && error.status === 401) result = null;
      else throw error;
    }
    if (sessionRequest !== controller) return false;
    if (result && (typeof result.nickname !== 'string' || !result.id)) throw new Error('Invalid session response');
    user = result;
    renderSession();
    return Boolean(user);
  } catch (error) {
    if (sessionRequest !== controller || error.name === 'AbortError') return false;
    user = null;
    renderSession('로그인 상태를 확인하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.');
    return false;
  } finally {
    if (sessionRequest === controller) { sessionRequest = null; renderState(); }
  }
}

function updateCounts() {
  $('title-count').textContent = `${title.value.length.toLocaleString('ko-KR')} / 200`;
  $('content-count').textContent = `${content.value.length.toLocaleString('ko-KR')} / 10,000`;
  $('title-count').classList.toggle('is-invalid', title.value.length > 200);
  $('content-count').classList.toggle('is-invalid', content.value.length > 10000);
}

function validateText({ focus = true } = {}) {
  const { title: titleError, content: contentError } = postTextErrors(title.value, content.value);
  showError('title-error', titleError);
  showError('content-error', contentError);
  title.setAttribute('aria-invalid', String(Boolean(titleError)));
  content.setAttribute('aria-invalid', String(Boolean(contentError)));
  if (titleError || contentError) {
    if (focus) (titleError ? title : content).focus();
    return false;
  }
  return true;
}

function renderFiles() {
  const list = $('file-list');
  list.replaceChildren();
  list.hidden = !files.length;
  $('file-count').textContent = `${files.length} / 5개`;
  files.forEach((file, index) => {
    const item = element('li', 'editor-file-item');
    const name = element('span', 'editor-file-name', file.name);
    const size = element('span', 'editor-file-size', formatFileSize(file.size));
    const remove = element('button', 'editor-file-remove', '삭제');
    remove.type = 'button';
    remove.setAttribute('aria-label', `${file.name} 첨부 취소`);
    remove.addEventListener('click', () => {
      if (busy || uncertain) return;
      files.splice(index, 1);
      showError('file-error', '');
      renderFiles();
      const next = list.querySelectorAll('button')[Math.min(index, files.length - 1)];
      (next ?? fileInput).focus();
    });
    item.append(name, size, remove);
    list.append(item);
  });
}

fileInput.addEventListener('change', () => {
  if (busy || uncertain) { fileInput.value = ''; return; }
  const candidate = [...files, ...Array.from(fileInput.files ?? [])];
  fileInput.value = '';
  const error = validateFiles(candidate);
  showError('file-error', error);
  if (error) return;
  files = candidate;
  renderFiles();
});

for (const input of [title, content]) input.addEventListener('input', () => {
  updateCounts();
  if (submitted) validateText({ focus: false });
});

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || uncertain) return;
  submitted = true;
  showError('submit-error', '');
  const validText = validateText();
  const fileError = validateFiles(files);
  showError('file-error', fileError);
  if (!validText || fileError) return;
  busy = true;
  renderState();
  try {
    if (!await refreshSession({ force: true })) {
      showError('submit-error', '로그인 상태를 확인한 뒤 다시 등록해 주세요. 작성한 내용과 선택한 파일은 유지돼요.');
      return;
    }
    const body = new FormData();
    body.append('post', new Blob([JSON.stringify({ title: title.value, content: content.value })], { type: 'application/json' }));
    for (const file of files) body.append('files', file, file.name);
    const controller = new AbortController();
    mutationRequest = controller;
    const result = await request('/api/posts', { method: 'POST', body, signal: controller.signal });
    const id = String(result?.id ?? '');
    if (!/^[1-9]\d{0,18}$/.test(id) || BigInt(id) > 9223372036854775807n) {
      throw new ApiError('등록 결과를 확인하지 못했어요.', 201, true);
    }
    leaving = true;
    uncertain = false;
    const params = new URLSearchParams(listUrl().slice(2));
    params.set('id', id);
    location.replace(safeReturnUrl(`/post.html?${params}`));
  } catch (error) {
    uncertain = Boolean(error.mayHaveSucceeded);
    if (uncertain) showError('submit-error', '등록 결과를 확인하지 못했어요. 중복 등록을 막기 위해 자동으로 다시 전송하지 않아요.');
    else if (error.name !== 'AbortError') {
      if (error.status === 401) { user = null; renderSession(); }
      const message = error.status === 413 ? '제목·내용을 포함한 요청 크기가 너무 커요. 첨부파일을 줄여 다시 등록해 주세요.' : error.message;
      showError('submit-error', message || '글을 등록하지 못했어요. 입력 내용을 확인해 주세요.');
    }
  } finally {
    mutationRequest = null;
    busy = false;
    renderState();
  }
});

$('allow-retry').addEventListener('click', async () => {
  if (busy || !uncertain) return;
  const confirmed = await confirmAction({ title: '등록 여부를 확인하셨나요?', message: '이미 등록된 글을 다시 전송하면 같은 글이 두 번 등록될 수 있어요. 목록에서 확인한 뒤 계속해 주세요.', confirmLabel: '확인 후 계속 작성', danger: false });
  if (!confirmed) return;
  uncertain = false;
  showError('submit-error', '');
  renderState();
  await refreshSession();
});

document.querySelectorAll('[data-login-link]').forEach(link => { link.href = loginUrl(returnTo); });
document.querySelectorAll('[data-signup-link]').forEach(link => { link.href = signupUrl(returnTo); });
document.querySelectorAll('[data-list-link]').forEach(link => {
  link.href = listUrl();
  link.addEventListener('click', async event => {
    if (event.button > 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    if (!hasDraft() && !busy) return;
    event.preventDefault();
    if (busy) { notify('요청을 처리하고 있어요. 잠시 기다려 주세요.'); return; }
    const confirmed = await confirmAction({ title: '작성을 그만두시겠어요?', message: uncertain ? '등록 여부가 아직 확인되지 않았어요. 이 페이지를 떠나면 작성 중인 내용과 파일 선택이 사라져요.' : '작성 중인 내용과 파일 선택이 사라져요.', confirmLabel: '나가기', danger: false });
    if (confirmed) { leaving = true; location.assign(listUrl()); }
  });
});

$('refresh-session').addEventListener('click', () => refreshSession());
$('logout-button').addEventListener('click', async () => {
  if (busy) return;
  busy = true;
  sessionRequest?.abort();
  sessionRequest = null;
  renderState();
  const controller = new AbortController();
  try {
    await request('/api/auth/logout', { method: 'POST', signal: controller.signal });
    user = null;
    renderSession();
    notify('로그아웃했어요. 작성 중인 내용과 파일은 유지돼요.');
  } catch (error) { notify(error.message); }
  finally { busy = false; renderState(); await refreshSession(); }
});

window.addEventListener('beforeunload', event => {
  if (!leaving && (hasDraft() || busy)) { event.preventDefault(); event.returnValue = ''; }
});
window.addEventListener('pagehide', () => {
  sessionRequest?.abort();
  if (mutationRequest && !leaving) { uncertain = true; mutationRequest.abort(); }
});
window.addEventListener('pageshow', event => { if (event.persisted) { leaving = false; refreshSession(); } });
window.addEventListener('focus', () => refreshSession());
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') refreshSession(); });
updateCounts();
renderFiles();
renderState();
refreshSession();
