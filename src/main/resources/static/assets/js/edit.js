import { ApiError, request } from './api.js';
import { loginUrl, signupUrl, safeReturnUrl, editUrl } from './navigation.js';
import { confirmAction, element, listUrl, notify } from './ui.js';
import { postTextErrors, validateFiles, formatFileSize } from './editor-fields.js';

const $ = id => document.getElementById(id);
const params = new URLSearchParams(location.search);
const postId = params.has('preview') ? null : positiveId(params.get('id'));
const detailUrl = postId ? safeReturnUrl(`/post.html?id=${postId}&${listUrl().slice(2)}`) : listUrl();
const returnTo = postId ? editUrl(postId) : '/';
const form = $('edit-form');
const title = $('post-title');
const content = $('post-content');
const fileInput = $('file-input');
const fields = $('post-fields');
let original = null;
let loadedFields = { title: '', content: '' };
let originalFiles = [];
let newFiles = [];
const deletedIds = new Set();
let user = null;
let loading = false;
let busy = false;
let recovery = '';
let submitted = false;
let leaving = false;
let loadRequest;
let sessionRequest;
let mutationRequest;

function positiveId(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return null;
  const text = String(value ?? '');
  return /^[1-9]\d{0,18}$/.test(text) && BigInt(text) <= 9223372036854775807n ? text : null;
}

const isAuthor = () => Boolean(original && user && positiveId(user.id) === positiveId(original.author.id));
const hasDraft = () => Boolean(original && (title.value !== loadedFields.title || content.value !== loadedFields.content
  || newFiles.length || deletedIds.size));
const canChange = () => Boolean(original && isAuthor() && !busy && !loading);

function showError(id, message = '') {
  $(id).textContent = message;
  $(id).hidden = !message;
}

function renderState() {
  form.hidden = !original;
  fields.disabled = !canChange();
  $('post-submit').disabled = !canChange() || Boolean(recovery) || !hasDraft();
  $('post-submit').textContent = busy ? '처리 중…' : '수정 저장';
  form.setAttribute('aria-busy', String(busy || loading));
  $('logout-button').disabled = busy || loading;
  $('refresh-session').disabled = busy || loading || Boolean(sessionRequest);
  $('retry-load').disabled = loading;
  $('reload-latest').disabled = busy || loading;
  $('reload-latest').textContent = loading ? '불러오는 중…' : '최신 내용 다시 불러오기';
  $('recovery-panel').hidden = !recovery;
  $('recovery-message').textContent = recovery === 'conflict'
    ? '다른 탭에서 글이나 첨부파일이 변경되었어요. 입력은 유지했어요. 현재 게시글을 확인한 뒤 최신 내용을 불러와 주세요.'
    : '수정이 이미 저장되었을 수 있어요. 현재 게시글을 확인한 뒤 최신 내용을 불러와 주세요.';
}

function renderSession(message) {
  $('session-name').hidden = !user;
  $('session-name').textContent = user ? `${user.nickname}님` : '';
  $('logout-button').hidden = !user;
  document.querySelectorAll('.account-actions [data-login-link], .account-actions [data-signup-link]')
    .forEach(link => { link.hidden = Boolean(user); });
  $('guest-notice').hidden = Boolean(user) || !postId;
  $('refresh-session').hidden = Boolean(user) && !message && (!original || isAuthor());
  $('session-status').textContent = message ?? (!postId ? '게시글 주소를 확인해 주세요.'
    : !user ? '글을 작성한 계정으로 로그인하면 수정할 수 있어요.'
      : !original ? `${user.nickname}님으로 로그인되어 있어요.`
        : isAuthor() ? `${user.nickname}님이 작성한 글을 수정하고 있어요.` : '이 글은 작성한 계정으로만 수정할 수 있어요.');
  renderState();
}

async function refreshSession({ force = false } = {}) {
  if (!postId || (busy && !force)) return false;
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
    if (result && (!positiveId(result.id) || typeof result.nickname !== 'string')) throw new Error('Invalid session response');
    user = result;
    renderSession();
    return isAuthor();
  } catch (error) {
    if (sessionRequest !== controller || error.name === 'AbortError') return false;
    user = null;
    renderSession('로그인 상태를 확인하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.');
    return false;
  } finally {
    if (sessionRequest === controller) { sessionRequest = null; renderState(); }
  }
}

function validateResponse(post, attachments) {
  if (positiveId(post?.id) !== postId || !positiveId(post?.author?.id)
      || typeof post.title !== 'string' || typeof post.content !== 'string'
      || typeof post.updatedAt !== 'string' || !Number.isFinite(Date.parse(post.updatedAt))
      || !Array.isArray(attachments) || attachments.length > 1000) throw new Error('Invalid post response');
  const ids = new Set();
  for (const attachment of attachments) {
    const id = positiveId(attachment?.id);
    if (!id || ids.has(id) || typeof attachment.originalFilename !== 'string'
        || !Number.isSafeInteger(attachment.size) || attachment.size < 0) throw new Error('Invalid attachment response');
    ids.add(id);
  }
}

async function loadPost() {
  if (!postId || loading || busy) return;
  const controller = new AbortController();
  loadRequest = controller;
  loading = true;
  if (!original) {
    $('load-panel').hidden = false;
    $('load-title').textContent = '게시글을 불러오고 있어요';
    $('load-message').textContent = '제목과 내용, 첨부파일을 확인하고 있어요.';
    $('retry-load').hidden = true;
  }
  $('load-panel').setAttribute('aria-busy', 'true');
  renderState();
  try {
    const [post, attachments] = await Promise.all([
      request(`/api/posts/${postId}`, { signal: controller.signal }),
      request(`/api/posts/${postId}/files`, { signal: controller.signal })
    ]);
    if (loadRequest !== controller || controller.signal.aborted) return;
    validateResponse(post, attachments);
    original = post;
    originalFiles = attachments;
    newFiles = [];
    deletedIds.clear();
    recovery = '';
    submitted = false;
    title.value = post.title;
    content.value = post.content;
    // Browsers normalize line endings in controls; an untouched field must retain its stored text.
    loadedFields = { title: title.value, content: content.value };
    fileInput.value = '';
    for (const id of ['title-error', 'content-error', 'file-error', 'submit-error']) showError(id);
    title.setAttribute('aria-invalid', 'false');
    content.setAttribute('aria-invalid', 'false');
    updateCounts();
    renderExistingFiles();
    renderNewFiles();
    $('load-panel').hidden = true;
    renderSession();
  } catch (error) {
    if (loadRequest !== controller || error.name === 'AbortError') return;
    controller.abort();
    const message = error.status === 404 ? '게시글이 삭제되었거나 존재하지 않아요.' : '게시글과 첨부파일을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.';
    if (original) showError('submit-error', message + ' 수정 중인 내용은 유지했어요.');
    else {
      $('load-title').textContent = error.status === 404 ? '게시글을 찾을 수 없어요' : '불러오지 못했어요';
      $('load-message').textContent = message;
      $('retry-load').hidden = error.status === 404;
    }
  } finally {
    if (loadRequest === controller) {
      loadRequest = null;
      loading = false;
      $('load-panel').setAttribute('aria-busy', 'false');
      renderState();
    }
  }
}

function updateCounts() {
  $('title-count').textContent = `${title.value.length.toLocaleString('ko-KR')} / 200`;
  $('content-count').textContent = `${content.value.length.toLocaleString('ko-KR')} / 10,000`;
  $('title-count').classList.toggle('is-invalid', title.value.length > 200);
  $('content-count').classList.toggle('is-invalid', content.value.length > 10000);
}

function validateText({ focus = true } = {}) {
  const errors = postTextErrors(title.value, content.value);
  showError('title-error', errors.title);
  showError('content-error', errors.content);
  title.setAttribute('aria-invalid', String(Boolean(errors.title)));
  content.setAttribute('aria-invalid', String(Boolean(errors.content)));
  if (errors.title || errors.content) {
    if (focus) (errors.title ? title : content).focus();
    return false;
  }
  return true;
}

function renderExistingFiles() {
  const list = $('existing-file-list');
  list.replaceChildren();
  $('no-existing-files').hidden = Boolean(originalFiles.length);
  $('existing-file-count').textContent = deletedIds.size ? `${originalFiles.length}개 · ${deletedIds.size}개 삭제 예정` : `${originalFiles.length}개`;
  originalFiles.forEach((file, index) => {
    const id = positiveId(file.id);
    const removed = deletedIds.has(id);
    const item = element('li', `editor-file-item${removed ? ' is-removed' : ''}`);
    const name = element('span', 'editor-file-name');
    const download = element('a', '', file.originalFilename);
    download.href = `/api/files/${id}/download`;
    download.setAttribute('download', '');
    name.append(download);
    if (removed) name.append(element('span', 'editor-file-badge', '삭제 예정'));
    const size = element('span', 'editor-file-size', formatFileSize(file.size));
    const toggle = element('button', 'editor-file-remove', removed ? '삭제 취소' : '삭제');
    toggle.type = 'button';
    toggle.setAttribute('aria-label', `${file.originalFilename} ${removed ? '삭제 취소' : '삭제 예약'}`);
    toggle.setAttribute('aria-pressed', String(removed));
    toggle.addEventListener('click', () => {
      if (!canChange()) return;
      if (deletedIds.has(id)) deletedIds.delete(id);
      else deletedIds.add(id);
      renderExistingFiles();
      renderState();
      list.querySelectorAll('button')[index]?.focus();
    });
    item.append(name, size, toggle);
    list.append(item);
  });
}

function renderNewFiles() {
  const list = $('file-list');
  list.replaceChildren();
  list.hidden = !newFiles.length;
  $('file-count').textContent = `${newFiles.length} / 5개`;
  newFiles.forEach((file, index) => {
    const item = element('li', 'editor-file-item');
    const remove = element('button', 'editor-file-remove', '취소');
    remove.type = 'button';
    remove.setAttribute('aria-label', `${file.name} 새 첨부 취소`);
    remove.addEventListener('click', () => {
      if (!canChange()) return;
      newFiles.splice(index, 1);
      showError('file-error');
      renderNewFiles();
      renderState();
      (list.querySelectorAll('button')[Math.min(index, newFiles.length - 1)] ?? fileInput).focus();
    });
    item.append(element('span', 'editor-file-name', file.name), element('span', 'editor-file-size', formatFileSize(file.size)), remove);
    list.append(item);
  });
}

fileInput.addEventListener('change', () => {
  if (!canChange()) { fileInput.value = ''; return; }
  const candidate = [...newFiles, ...Array.from(fileInput.files ?? [])];
  fileInput.value = '';
  const error = validateFiles(candidate);
  showError('file-error', error);
  if (error) return;
  newFiles = candidate;
  renderNewFiles();
  renderState();
});
for (const input of [title, content]) input.addEventListener('input', () => {
  updateCounts();
  if (submitted) validateText({ focus: false });
  renderState();
});

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (!canChange() || recovery) return;
  if (!hasDraft()) { notify('변경한 내용이 없어요.'); return; }
  submitted = true;
  showError('submit-error');
  const textValid = validateText();
  const fileError = validateFiles(newFiles);
  showError('file-error', fileError);
  if (!textValid || fileError) return;
  busy = true;
  renderState();
  try {
    if (!await refreshSession({ force: true })) {
      showError('submit-error', '글을 작성한 계정으로 로그인한 뒤 다시 저장해 주세요. 수정 중인 내용과 파일 선택은 유지돼요.');
      return;
    }
    const body = new FormData();
    body.append('post', new Blob([JSON.stringify({
      title: title.value === loadedFields.title ? original.title : title.value,
      content: content.value === loadedFields.content ? original.content : content.value,
      updatedAt: original.updatedAt,
      attachmentIds: originalFiles.map(file => file.id),
      deletedFileIds: originalFiles.filter(file => deletedIds.has(positiveId(file.id))).map(file => file.id)
    })], { type: 'application/json' }));
    for (const file of newFiles) body.append('files', file, file.name);
    const controller = new AbortController();
    mutationRequest = controller;
    const result = await request(`/api/posts/${postId}`, { method: 'PUT', body, signal: controller.signal });
    if (positiveId(result?.id) !== postId) throw new ApiError('저장 결과를 확인하지 못했어요.', 200, true);
    leaving = true;
    recovery = '';
    location.replace(detailUrl);
  } catch (error) {
    if (error.mayHaveSucceeded) {
      recovery = 'unknown';
      showError('submit-error', '저장 결과를 확인하지 못했어요. 같은 파일을 중복 첨부하지 않도록 자동으로 다시 전송하지 않아요.');
    } else if (error.status === 409) {
      recovery = 'conflict';
      showError('submit-error', '게시글 또는 첨부파일이 변경되어 저장하지 않았어요.');
    } else if (error.name !== 'AbortError') {
      if (error.status === 401) { user = null; renderSession(); }
      const message = error.status === 413 ? '제목·내용을 포함한 요청 크기가 너무 커요. 새 첨부파일을 줄여 다시 저장해 주세요.'
        : error.status === 403 ? '저장 권한 또는 로그인 상태를 확인해 주세요. 작성한 내용은 유지돼요.'
          : error.status === 404 ? '게시글이나 첨부파일이 삭제되었을 수 있어요. 현재 게시글을 확인해 주세요.' : error.message;
      showError('submit-error', message || '글을 수정하지 못했어요. 입력 내용을 확인해 주세요.');
      if (error.status === 403) await refreshSession({ force: true });
      if (error.status === 404) recovery = 'conflict';
    } else if (!error.mayHaveSucceeded) recovery = '';
  } finally {
    mutationRequest = null;
    busy = false;
    renderState();
  }
});

$('reload-latest').addEventListener('click', async () => {
  if (busy || loading || !original) return;
  const confirmed = await confirmAction({ title: '최신 내용을 불러올까요?',
    message: '수정 중인 내용, 파일 삭제 선택과 새 파일 선택이 사라지고 현재 저장된 게시글로 바뀌어요. 필요한 내용은 먼저 복사해 주세요.',
    confirmLabel: '최신 내용 불러오기', danger: false });
  if (confirmed) await loadPost();
});
$('retry-load').addEventListener('click', () => { if (!original) loadPost(); });
$('refresh-session').addEventListener('click', () => refreshSession());
$('check-post-link').href = detailUrl;
document.querySelectorAll('[data-login-link]').forEach(link => { link.href = loginUrl(returnTo); });
document.querySelectorAll('[data-signup-link]').forEach(link => { link.href = signupUrl(returnTo); });

function bindNavigation(selector, destination) {
  document.querySelectorAll(selector).forEach(link => {
    link.href = destination;
    link.addEventListener('click', async event => {
      if (event.button > 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      if (!hasDraft() && !busy && !recovery) return;
      event.preventDefault();
      if (busy || loading) { notify('요청을 처리하고 있어요. 잠시 기다려 주세요.'); return; }
      const confirmed = await confirmAction({ title: '수정을 그만두시겠어요?',
        message: recovery ? '현재 저장 결과를 확인해 주세요. 이 페이지를 떠나면 수정 중인 내용과 파일 선택이 사라져요.'
          : '수정 중인 내용과 파일 선택이 사라져요. 저장하기 전 선택한 파일 삭제는 반영되지 않아요.',
        confirmLabel: '나가기', danger: false });
      if (confirmed) { leaving = true; location.assign(destination); }
    });
  });
}
bindNavigation('[data-list-link]', listUrl());
bindNavigation('[data-detail-link]', detailUrl);

$('logout-button').addEventListener('click', async () => {
  if (busy || loading || !user) return;
  busy = true;
  sessionRequest?.abort();
  sessionRequest = null;
  renderState();
  try {
    await request('/api/auth/logout', { method: 'POST' });
    user = null;
    renderSession();
    notify('로그아웃했어요. 수정 중인 내용과 파일 선택은 유지돼요.');
  } catch (error) { notify(error.message); }
  finally { busy = false; renderState(); await refreshSession(); }
});

window.addEventListener('beforeunload', event => {
  if (!leaving && (hasDraft() || busy || recovery)) { event.preventDefault(); event.returnValue = ''; }
});
window.addEventListener('pagehide', () => {
  sessionRequest?.abort();
  loadRequest?.abort();
  loadRequest = null;
  loading = false;
  if (mutationRequest && !leaving) { recovery = 'unknown'; mutationRequest.abort(); }
});
window.addEventListener('pageshow', event => {
  if (event.persisted) {
    leaving = false;
    if (!original) loadPost();
    refreshSession();
  }
});
window.addEventListener('focus', () => refreshSession());
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') refreshSession(); });
updateCounts();
renderState();
if (postId) { loadPost(); refreshSession(); }
else {
  $('load-title').textContent = '올바른 게시글 주소가 아니에요';
  $('load-message').textContent = '게시글 상세 화면에서 수정 버튼을 눌러 주세요.';
  $('load-panel').setAttribute('aria-busy', 'false');
  $('retry-load').hidden = true;
  renderSession();
}
