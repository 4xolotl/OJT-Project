import { request } from './api.js';
import { editUrl, loginUrl, signupUrl } from './navigation.js';
import { element, notify, confirmAction, listUrl } from './ui.js';

const $ = id => document.getElementById(id);
const params = new URLSearchParams(location.search);
const preview = params.get('preview') === '1';
const postId = params.get('id') ?? '';
const backUrl = listUrl();
const commentSize = 10;
const dateFormat = new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
let post;
let user = null;
let sessionFailed = false;
let sessionRequest;
let sessionRefreshPending = false;
let comments;
let commentPage = 0;
let commentsLoading = false;
let commentRequest;
let editingId = null;
let busy = false;
let editNavigationPending = false;

document.querySelectorAll('[data-list-link]').forEach(link => { link.href = backUrl; });

function formatDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : dateFormat.format(date);
}

function avatar(name) { return Array.from(name || '?')[0]; }
function owns(author) { return Boolean(user && author.id === user.id); }

function hasDraft() { return Boolean($('comment-content').value || editingId !== null); }

function updateAuthLinks() {
  document.querySelectorAll('[data-login-link], [data-signup-link]').forEach(link => {
    const signingUp = link.dataset.signupLink !== undefined;
    const label = signingUp ? '회원가입' : '로그인';
    if (preview || busy) {
      link.removeAttribute('href');
      link.setAttribute('tabindex', '-1');
    } else {
      link.href = signingUp ? signupUrl() : loginUrl();
      link.removeAttribute('tabindex');
    }
    link.rel = 'noopener';
    link.setAttribute('aria-disabled', String(preview || busy));
    if (hasDraft()) {
      link.target = '_blank';
      link.title = `작성 중인 댓글을 유지하고 새 탭에서 ${label}`;
      link.setAttribute('aria-label', `${label} (새 탭에서 열림)`);
    } else {
      link.removeAttribute('target');
      link.removeAttribute('title');
      link.removeAttribute('aria-label');
    }
  });
}

document.addEventListener('click', event => {
  const link = event.target.closest('[data-login-link], [data-signup-link]');
  if (!link) return;
  if (preview || busy) { event.preventDefault(); return; }
  updateAuthLinks();
  if (hasDraft()) notify(link.dataset.signupLink !== undefined
    ? '새 탭에서 회원가입하고 로그인한 뒤 이 화면으로 돌아오세요. 작성한 내용은 그대로 유지돼요.'
    : '새 탭에서 로그인한 뒤 이 화면으로 돌아오세요. 작성한 내용은 그대로 유지돼요.');
});

function refreshControls() {
  document.querySelectorAll('[data-auth-action]').forEach(button => { button.disabled = busy || preview || !user; });
  document.querySelectorAll('[data-comment-action]').forEach(button => { button.disabled = busy || commentsLoading || preview || !user; });
  document.querySelectorAll('[data-author-id]').forEach(button => { button.disabled = busy || preview || !user || button.dataset.authorId !== String(user.id); });
  $('comment-content').disabled = busy || preview || !user;
  const editDisabled = busy || preview || editNavigationPending || !owns(post?.author ?? {});
  $('post-edit').setAttribute('aria-disabled', String(editDisabled));
  if (editDisabled) {
    $('post-edit').removeAttribute('href');
    $('post-edit').setAttribute('tabindex', '-1');
  } else {
    $('post-edit').href = editUrl(postId);
    $('post-edit').removeAttribute('tabindex');
  }
  $('comment-previous').disabled = busy || commentsLoading || editingId !== null || !comments || comments.first;
  $('comment-next').disabled = busy || commentsLoading || editingId !== null || !comments || comments.last;
  $('comment-retry').disabled = busy || commentsLoading || editingId !== null;
  $('comment-refresh').disabled = busy || commentsLoading || editingId !== null;
  $('session-retry').disabled = busy || Boolean(sessionRequest);
  $('comment-submit').textContent = busy ? '처리 중…' : '댓글 등록';
  updateAuthLinks();
}

function setBusy(value) {
  busy = value;
  if (value) {
    sessionRequest?.abort();
    sessionRequest = null;
  }
  refreshControls();
  if (!value && sessionRefreshPending) { sessionRefreshPending = false; loadSession(); }
}

function renderAccount() {
  const container = $('account-actions');
  container.replaceChildren();
  if (user) {
    const name = element('span', 'session-name', `${user.nickname}님`);
    name.title = user.nickname;
    const logout = element('button', 'button button-outline button-small', '로그아웃');
    logout.type = 'button';
    logout.dataset.authAction = '';
    logout.addEventListener('click', logoutUser);
    container.append(name, logout);
  } else {
    const login = element(preview ? 'button' : 'a', 'button button-ghost button-small', '로그인');
    if (preview) {
      login.type = 'button';
      login.disabled = true;
      login.title = '미리보기에서는 로그인할 수 없어요.';
    } else {
      login.dataset.loginLink = '';
    }
    const signup = element(preview ? 'button' : 'a', 'button button-outline button-small', '회원가입');
    if (preview) {
      signup.type = 'button';
      signup.disabled = true;
      signup.title = '미리보기에서는 회원가입할 수 없어요.';
    } else {
      signup.dataset.signupLink = '';
    }
    container.append(login, signup);
  }
  updateAuthLinks();
}

function renderPermissions() {
  $('post-owner-actions').hidden = !preview && !owns(post?.author ?? {});
  $('comment-guest').hidden = preview || Boolean(user);
  // Keep a failed or expired-session draft in the DOM, even after authentication changes.
  $('comment-form').hidden = !preview && !user && !$('comment-content').value;
  $('guest-title').textContent = sessionFailed ? '로그인 상태를 확인하지 못했어요' : '함께 이야기를 나눠보세요';
  $('guest-description').textContent = sessionFailed ? '연결 상태를 확인한 뒤 다시 확인해 주세요.' : '댓글을 남기려면 로그인이 필요해요.';
  $('comment-login').hidden = sessionFailed;
  $('session-retry').hidden = preview || Boolean(user);
  $('session-retry').textContent = sessionFailed ? '다시 확인' : '로그인 상태 확인';
  document.querySelectorAll('[data-owner-controls]').forEach(node => {
    node.hidden = !user || node.dataset.ownerId !== String(user.id) || node.dataset.commentId === String(editingId);
  });
  refreshControls();
}

function reportError(error, target) {
  let message = error.message || '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.';
  if (error.status === 401) {
    user = null;
    sessionFailed = false;
    message = '로그인이 만료됐어요. 작성한 내용은 유지됩니다. 다시 로그인한 뒤 확인해 주세요.';
    renderAccount();
    renderPermissions();
    document.querySelectorAll('[data-owner-controls]').forEach(node => { node.hidden = true; });
  }
  if (target) { target.textContent = message; target.hidden = false; }
  notify(message);
}

function showPageState(title, description, retry = false) {
  $('post-view').hidden = true;
  $('page-state').hidden = false;
  $('page-state-title').textContent = title;
  $('page-state-description').textContent = description;
  $('page-retry').hidden = !retry;
  document.title = `${title} · BOARD`;
}

function renderPost() {
  $('page-state').hidden = true;
  $('post-view').hidden = false;
  $('post-title').textContent = post.title;
  $('post-content').textContent = post.content;
  $('post-author').textContent = post.author.nickname;
  $('post-avatar').textContent = avatar(post.author.nickname);
  $('post-date').textContent = formatDate(post.createdAt);
  $('post-date').dateTime = post.createdAt;
  $('post-edited').hidden = post.updatedAt === post.createdAt;
  $('post-edited').title = `최종 수정 ${formatDate(post.updatedAt)}`;
  document.title = `${preview ? '[미리보기] ' : ''}${post.title} · BOARD`;
  renderPermissions();
}

function fileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function renderFiles(files) {
  $('file-list').replaceChildren();
  $('file-count').textContent = files.length;
  $('file-status').hidden = files.length > 0;
  $('file-status').textContent = '첨부된 파일이 없어요.';
  $('file-retry').hidden = true;
  files.forEach(file => {
    const link = element(preview ? 'button' : 'a', 'file-item');
    if (preview) {
      link.type = 'button';
      link.addEventListener('click', () => notify('미리보기의 첨부파일은 예시이며 다운로드할 수 없어요.'));
    } else {
      link.href = `/api/files/${encodeURIComponent(file.id)}/download`;
      link.download = file.originalFilename;
    }
    link.setAttribute('aria-label', `${file.originalFilename} ${preview ? '미리보기' : '다운로드'}`);
    link.title = file.originalFilename;
    const extension = file.originalFilename.split('.').pop().slice(0, 4).toUpperCase();
    const mark = element('span', 'file-mark', extension);
    mark.setAttribute('aria-hidden', 'true');
    const info = element('span', 'file-info');
    info.append(element('span', 'file-name', file.originalFilename), element('span', 'file-size', `${fileSize(file.size)}${preview ? ' · 예시 파일' : ''}`));
    const arrow = element('span', 'file-download', '↓');
    arrow.setAttribute('aria-hidden', 'true');
    link.append(mark, info, arrow);
    $('file-list').append(link);
  });
}

async function loadFiles() {
  $('file-retry').hidden = true;
  $('file-status').hidden = false;
  $('file-status').textContent = '첨부파일을 확인하고 있어요.';
  try { renderFiles(await request(`/api/posts/${postId}/files`)); }
  catch (error) {
    $('file-status').textContent = error.status === 404 ? '게시글이 삭제되어 첨부파일을 볼 수 없어요.' : '첨부파일을 불러오지 못했어요.';
    $('file-retry').hidden = false;
  }
}

async function loadSession() {
  if (preview) return;
  if (busy || sessionRequest) { sessionRefreshPending = true; return; }
  const controller = new AbortController();
  sessionRequest = controller;
  refreshControls();
  try {
    const nextUser = await request('/api/auth/me', { signal: controller.signal });
    if (controller !== sessionRequest) return;
    user = nextUser;
    sessionFailed = false;
  } catch (error) {
    if (controller !== sessionRequest) return;
    user = null;
    sessionFailed = error.status !== 401;
  } finally {
    if (controller === sessionRequest) {
      sessionRequest = null;
      renderAccount();
      // Authentication refresh only updates permissions, preserving comment DOM and drafts.
      renderPermissions();
      if (sessionRefreshPending) { sessionRefreshPending = false; loadSession(); }
    }
  }
}

function commentButton(label, callback, danger = false) {
  const button = element('button', `button button-ghost button-small${danger ? ' text-danger' : ''}`, label);
  button.type = 'button';
  button.dataset.authAction = '';
  button.dataset.commentAction = '';
  button.addEventListener('click', callback);
  return button;
}

function renderComments() {
  const list = $('comment-list');
  list.replaceChildren();
  $('comment-count').textContent = comments.totalElements.toLocaleString('ko-KR');
  $('comment-status').hidden = comments.content.length > 0;
  $('comment-status').textContent = '아직 댓글이 없어요. 첫 번째 의견을 남겨보세요.';
  $('comment-retry').hidden = true;
  comments.content.forEach(comment => {
    const item = element('li', 'comment-item');
    const picture = element('span', 'avatar', avatar(comment.author.nickname));
    picture.setAttribute('aria-hidden', 'true');
    const main = element('div', 'comment-main');
    const meta = element('div', 'comment-meta');
    meta.append(element('span', 'comment-author', comment.author.nickname));
    if (comment.author.id === post.author.id) meta.append(element('span', 'comment-author-badge', '작성자'));
    const time = element('time', 'comment-date', `${formatDate(comment.createdAt)}${comment.updatedAt !== comment.createdAt ? ' · 수정됨' : ''}`);
    time.dateTime = comment.createdAt;
    meta.append(time);
    const content = element('p', 'comment-content', comment.content);
    main.append(meta, content);
    if (!preview) {
      const actions = element('div', 'comment-actions');
      actions.dataset.ownerControls = '';
      actions.dataset.ownerId = String(comment.author.id);
      actions.dataset.commentId = String(comment.id);
      actions.hidden = !owns(comment.author);
      actions.append(commentButton('수정', () => editComment(comment, main, content, actions)), commentButton('삭제', () => deleteComment(comment), true));
      main.append(actions);
    }
    item.append(picture, main);
    list.append(item);
  });
  $('comment-pagination').hidden = comments.totalPages < 2;
  $('comment-page-summary').textContent = `${comments.page + 1} / ${comments.totalPages} 페이지`;
  refreshControls();
}

async function loadComments(page = commentPage, lastPage = false) {
  if (editingId !== null) return;
  commentRequest?.abort();
  const controller = new AbortController();
  commentRequest = controller;
  commentsLoading = true;
  $('comment-results').setAttribute('aria-busy', 'true');
  $('comment-status').hidden = false;
  $('comment-status').textContent = '댓글을 불러오고 있어요.';
  $('comment-retry').hidden = true;
  refreshControls();
  try {
    let data = await request(`/api/posts/${postId}/comments?page=${page}&size=${commentSize}`, { signal: controller.signal });
    const targetPage = lastPage ? Math.max(0, data.totalPages - 1) : Math.min(page, Math.max(0, data.totalPages - 1));
    if (targetPage !== page) data = await request(`/api/posts/${postId}/comments?page=${targetPage}&size=${commentSize}`, { signal: controller.signal });
    if (controller !== commentRequest) return;
    comments = data;
    commentPage = data.page;
    renderComments();
  } catch (error) {
    if (controller !== commentRequest) return;
    $('comment-status').hidden = false;
    $('comment-status').textContent = error.status === 404 ? '게시글이 삭제되어 댓글을 볼 수 없어요.' : '댓글을 불러오지 못했어요. 다시 시도해 주세요.';
    $('comment-retry').hidden = false;
  } finally {
    if (controller === commentRequest) {
      commentsLoading = false;
      $('comment-results').setAttribute('aria-busy', 'false');
      refreshControls();
    }
  }
}

function canMutate() {
  if (preview || busy) return false;
  if (!user) { notify('로그인이 필요해요. 로그인한 뒤 다시 시도해 주세요.'); return false; }
  return true;
}

function validContent(textarea, errorNode) {
  errorNode.hidden = true;
  if (textarea.value.trim()) return true;
  errorNode.textContent = '공백만으로는 댓글을 등록할 수 없어요.';
  errorNode.hidden = false;
  textarea.focus();
  return false;
}

$('comment-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (!canMutate()) return;
  if (editingId !== null) { notify('수정 중인 댓글을 먼저 저장하거나 취소해 주세요.'); return; }
  const input = $('comment-content');
  const errorNode = $('comment-form-error');
  if (!validContent(input, errorNode)) return;
  setBusy(true);
  try {
    await request(`/api/posts/${postId}/comments`, { method: 'POST', body: { content: input.value } });
    input.value = '';
    $('comment-length').textContent = '0 / 2,000';
    notify('댓글을 등록했어요.');
    await loadComments(commentPage, true);
  } catch (error) { reportError(error, errorNode); }
  finally { setBusy(false); }
});
$('comment-content').addEventListener('input', () => {
  $('comment-length').textContent = `${$('comment-content').value.length.toLocaleString('ko-KR')} / 2,000`;
  updateAuthLinks();
});

function editComment(comment, main, content, actions) {
  if (!canMutate() || !owns(comment.author)) return;
  if (commentsLoading) { notify('댓글을 불러온 뒤 수정해 주세요.'); return; }
  if (editingId !== null) { notify('수정 중인 댓글을 먼저 저장하거나 취소해 주세요.'); return; }
  editingId = comment.id;
  content.hidden = actions.hidden = true;
  const form = element('form', 'comment-edit-form');
  const label = element('label', 'sr-only', '댓글 수정 내용');
  const input = element('textarea', 'text-control');
  input.id = `edit-comment-${comment.id}`;
  label.htmlFor = input.id;
  input.rows = 3;
  input.maxLength = 2000;
  input.required = true;
  input.value = comment.content;
  const errorNode = element('p', 'form-error');
  errorNode.id = `edit-error-${comment.id}`;
  errorNode.setAttribute('role', 'alert');
  errorNode.hidden = true;
  input.setAttribute('aria-describedby', errorNode.id);
  const controls = element('div', 'comment-edit-actions');
  const cancel = element('button', 'button button-outline button-small', '취소');
  const save = element('button', 'button button-primary button-small', '저장');
  cancel.type = 'button';
  save.type = 'submit';
  save.dataset.authAction = '';
  save.dataset.authorId = String(comment.author.id);
  cancel.addEventListener('click', () => {
    if (busy) return;
    editingId = null;
    form.remove();
    content.hidden = false;
    actions.hidden = !owns(comment.author);
    refreshControls();
    if (!actions.hidden) actions.querySelector('button').focus();
  });
  controls.append(cancel, save);
  form.append(label, input, errorNode, controls);
  main.append(form);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (!canMutate() || !owns(comment.author) || !validContent(input, errorNode)) return;
    setBusy(true);
    input.disabled = cancel.disabled = true;
    try {
      const updated = await request(`/api/posts/${postId}/comments/${comment.id}`, { method: 'PUT', body: { content: input.value } });
      comments.content = comments.content.map(item => item.id === updated.id ? updated : item);
      editingId = null;
      renderComments();
      notify('댓글을 수정했어요.');
    } catch (error) { reportError(error, errorNode); }
    finally { input.disabled = cancel.disabled = false; setBusy(false); }
  });
  refreshControls();
  input.focus();
}

async function deleteComment(comment) {
  if (!canMutate() || !owns(comment.author)) return;
  if (editingId !== null) { notify('수정 중인 댓글을 먼저 저장하거나 취소해 주세요.'); return; }
  if (!await confirmAction({ title: '댓글을 삭제할까요?', message: '삭제한 댓글은 되돌릴 수 없어요.' })) return;
  if (!canMutate() || !owns(comment.author)) return;
  setBusy(true);
  try {
    await request(`/api/posts/${postId}/comments/${comment.id}`, { method: 'DELETE' });
    notify('댓글을 삭제했어요.');
    await loadComments();
  } catch (error) { reportError(error); }
  finally { setBusy(false); }
}

async function deletePost() {
  if (!canMutate() || !owns(post.author)) return;
  if (!await confirmAction({ title: '게시글을 삭제할까요?', message: '이 글의 댓글과 첨부파일도 모두 삭제돼요.\n삭제한 내용은 되돌릴 수 없어요.', confirmLabel: '게시글 삭제' })) return;
  if (!canMutate() || !owns(post.author)) return;
  setBusy(true);
  try {
    await request(`/api/posts/${postId}`, { method: 'DELETE' });
    location.assign(backUrl);
  } catch (error) { reportError(error); setBusy(false); }
}

async function navigateToEdit(event) {
  if (preview || busy || editNavigationPending || !owns(post?.author ?? {})) {
    event.preventDefault();
    return;
  }
  // Opening another tab leaves the current draft intact; preserve native link behavior.
  if (!hasDraft() || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey
      || event.button !== undefined && event.button !== 0) return;
  event.preventDefault();
  editNavigationPending = true;
  refreshControls();
  try {
    const confirmed = await confirmAction({
      title: '게시글 수정으로 이동할까요?',
      message: '저장하지 않은 댓글 내용은 사라져요. 게시글 수정 화면으로 이동할까요?',
      confirmLabel: '수정 화면으로 이동', danger: false
    });
    if (!confirmed || preview || busy || !owns(post?.author ?? {})) return;
    location.assign(editUrl(postId));
  } finally {
    editNavigationPending = false;
    refreshControls();
  }
}

async function logoutUser() {
  if (!canMutate()) return;
  if (($('comment-content').value || editingId !== null) && !await confirmAction({ title: '로그아웃할까요?', message: '입력 중인 댓글은 이 화면에 남지만, 저장하려면 다시 로그인해야 해요.', confirmLabel: '로그아웃', danger: false })) return;
  if (!canMutate()) return;
  setBusy(true);
  try {
    await request('/api/auth/logout', { method: 'POST' });
    user = null;
    sessionFailed = false;
    renderAccount();
    renderPermissions();
    document.querySelectorAll('[data-owner-controls]').forEach(node => { node.hidden = true; });
    notify('로그아웃했어요.');
  } catch (error) { reportError(error); sessionRefreshPending = true; }
  finally { setBusy(false); }
}

async function loadPage() {
  $('page-retry').hidden = true;
  try {
    post = await request(`/api/posts/${postId}`);
    renderPost();
    await Promise.allSettled([loadFiles(), loadComments(0)]);
  } catch (error) {
    if (error.status === 404) showPageState('게시글을 찾을 수 없어요', '삭제되었거나 존재하지 않는 글이에요. 목록에서 다른 이야기를 찾아보세요.');
    else showPageState('게시글을 불러오지 못했어요', '연결 상태를 확인한 뒤 다시 시도해 주세요.', true);
  }
}

function renderPreview() {
  $('preview-banner').hidden = false;
  $('comment-refresh').hidden = true;
  const createdAt = '2026-09-07T01:30:00Z';
  post = { id: 1, title: '작은 기록부터, 함께 시작해요', content: '안녕하세요, 여러분. 반갑습니다!\n\n하루를 보내다 문득 떠오른 생각이나 누군가와 나누고 싶은 이야기가 있나요?\n이곳이 그런 이야기들을 편하게 남길 수 있는 공간이 되었으면 좋겠어요.\n\n요즘 새롭게 배우고 있는 것, 나만 알고 있기 아쉬운 작은 팁,\n혹은 오늘 하루를 기분 좋게 만든 순간도 좋아요.\n\n저는 오늘부터 하루에 한 가지씩 배운 것을 기록해 보려고 합니다.\n여러분은 어떤 이야기를 나누고 싶으신가요? 댓글로 들려주세요. ☺', author: { id: 1, nickname: '기록하는 사람' }, createdAt, updatedAt: createdAt };
  comments = { content: [
    { id: 1, author: { id: 2, nickname: '오늘의 발견' }, content: '반갑습니다! 작은 기록이 쌓이면 나중에 좋은 추억이 될 것 같아요.\n저도 오늘 배운 것부터 하나씩 남겨볼게요.', createdAt: '2026-09-07T02:05:00Z', updatedAt: '2026-09-07T02:05:00Z' },
    { id: 2, author: post.author, content: '함께해 주셔서 감사해요. 부담 없이 편하게 이야기 나눠요!', createdAt: '2026-09-07T02:20:00Z', updatedAt: '2026-09-07T02:20:00Z' }
  ], totalElements: 2, totalPages: 1, page: 0, first: true, last: true };
  renderPost();
  renderFiles([{ id: 1, originalFilename: '나의 작은 기록 노트.txt', size: 1536 }]);
  renderComments();
  $('preview-comment-note').hidden = false;
  $('comment-results').setAttribute('aria-busy', 'false');
}

$('post-delete').addEventListener('click', deletePost);
$('post-edit').addEventListener('click', navigateToEdit);
$('file-retry').addEventListener('click', () => { if (!preview) loadFiles(); });
$('comment-retry').addEventListener('click', () => { if (!preview) loadComments(); });
$('comment-refresh').addEventListener('click', () => { if (!preview && !busy) loadComments(); });
$('session-retry').addEventListener('click', () => { if (!preview) loadSession(); });
$('comment-previous').addEventListener('click', () => { if (!preview && !busy && editingId === null && comments && !comments.first) loadComments(commentPage - 1); });
$('comment-next').addEventListener('click', () => { if (!preview && !busy && editingId === null && comments && !comments.last) loadComments(commentPage + 1); });
$('page-retry').addEventListener('click', () => { if (!preview) loadPage(); });
window.addEventListener('focus', () => { if (!preview) loadSession(); });
window.addEventListener('pageshow', event => { if (!preview && event.persisted) loadSession(); });
document.addEventListener('visibilitychange', () => { if (!preview && document.visibilityState === 'visible') loadSession(); });

renderAccount();
if (preview) renderPreview();
else if (!/^[1-9]\d{0,18}$/.test(postId) || BigInt(postId) > 9223372036854775807n) showPageState('올바르지 않은 게시글 주소예요', '목록에서 확인할 게시글을 선택해 주세요.');
else loadPage();
if (!preview) loadSession();
