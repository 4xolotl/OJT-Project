import { request } from './api.js';
import { loginUrl, signupUrl, writeUrl } from './navigation.js';
import { element, notify } from './ui.js';

const form = document.getElementById('search-form');
const search = document.getElementById('search');
const sizeSelect = document.getElementById('page-size');
const results = document.getElementById('results');
const list = document.getElementById('post-list');
const statePanel = document.getElementById('list-state');
const stateAction = document.getElementById('state-action');
const stateWrite = document.getElementById('state-write');
const summary = document.getElementById('list-summary');
const count = document.getElementById('post-count');
const pager = document.getElementById('pagination');
const paginationArea = document.getElementById('pagination-area');
const numberFormat = new Intl.NumberFormat('ko-KR');
const dateFormat = new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' });
let state;
let currentRequest;
let user = null;
let sessionFailed = false;
let sessionRequest;
let loggingOut = false;
let sessionRefreshPending = false;

function updateNavigationLinks() {
  document.querySelectorAll('[data-login-link]').forEach(link => { link.href = loginUrl(); });
  document.querySelectorAll('[data-signup-link]').forEach(link => { link.href = signupUrl(); });
  document.querySelectorAll('[data-write-link]').forEach(link => { link.href = writeUrl(); });
}

function renderAccount() {
  const account = document.getElementById('account-actions');
  account.replaceChildren();
  account.setAttribute('aria-busy', String(Boolean(sessionRequest) || loggingOut));
  if (user) {
    const name = element('span', 'session-name', `${user.nickname}님`);
    name.title = user.nickname;
    const logout = element('button', 'button button-outline button-small', loggingOut ? '로그아웃 중…' : '로그아웃');
    logout.type = 'button';
    logout.disabled = loggingOut;
    logout.addEventListener('click', logoutUser);
    account.append(name, logout);
  } else {
    if (sessionFailed) {
      const retry = element('button', 'button button-ghost button-small', '로그인 상태 재확인');
      retry.type = 'button';
      retry.disabled = Boolean(sessionRequest);
      retry.title = '연결 문제로 로그인 상태를 확인하지 못했어요.';
      retry.addEventListener('click', loadSession);
      account.append(retry);
    }
    const login = element('a', 'button button-ghost button-small', '로그인');
    login.dataset.loginLink = '';
    login.href = loginUrl();
    const signup = element('a', 'button button-outline button-small', '회원가입');
    signup.dataset.signupLink = '';
    signup.href = signupUrl();
    account.append(login, signup);
  }
}

async function loadSession() {
  if (loggingOut) { sessionRefreshPending = true; return; }
  if (sessionRequest) { sessionRefreshPending = true; return; }
  const controller = new AbortController();
  sessionRequest = controller;
  document.getElementById('account-actions').setAttribute('aria-busy', 'true');
  try {
    const nextUser = await request('/api/auth/me', { signal: controller.signal });
    if (sessionRequest !== controller) return;
    user = nextUser;
    sessionFailed = false;
  } catch (error) {
    if (sessionRequest !== controller) return;
    user = null;
    sessionFailed = error.status !== 401;
  } finally {
    if (sessionRequest === controller) {
      sessionRequest = null;
      renderAccount();
      if (sessionRefreshPending) { sessionRefreshPending = false; loadSession(); }
    }
  }
}

async function logoutUser() {
  if (loggingOut || !user) return;
  loggingOut = true;
  sessionRequest?.abort();
  sessionRequest = null;
  renderAccount();
  try {
    await request('/api/auth/logout', { method: 'POST' });
    user = null;
    sessionFailed = false;
    notify('로그아웃했어요.');
  } catch (error) {
    notify(error.message || '로그아웃하지 못했어요. 다시 시도해 주세요.');
    sessionRefreshPending = true;
  } finally {
    loggingOut = false;
    renderAccount();
    if (sessionRefreshPending) { sessionRefreshPending = false; loadSession(); }
  }
}

function readState() {
  const params = new URLSearchParams(location.search);
  const page = Number(params.get('page') ?? 0);
  const size = Number(params.get('size') ?? 20);
  return {
    page: Number.isInteger(page) && page >= 0 && page <= 2147483647 ? page : 0,
    size: [10, 20, 50].includes(size) ? size : 20,
    keyword: (params.get('keyword') ?? '').trim().slice(0, 100)
  };
}

function syncUrl(replace = false) {
  const params = new URLSearchParams();
  if (state.keyword) params.set('keyword', state.keyword);
  if (state.page) params.set('page', state.page);
  if (state.size !== 20) params.set('size', state.size);
  const query = params.toString();
  const url = `${location.pathname}${query ? `?${query}` : ''}${location.hash}`;
  if (url !== `${location.pathname}${location.search}${location.hash}`) {
    history[replace ? 'replaceState' : 'pushState'](null, '', url);
  }
  updateNavigationLinks();
}

function showState(title, description, actionLabel, action) {
  list.replaceChildren();
  statePanel.hidden = false;
  document.getElementById('state-title').textContent = title;
  document.getElementById('state-description').textContent = description;
  stateAction.hidden = !actionLabel;
  stateAction.textContent = actionLabel || '';
  stateAction.onclick = action || null;
  stateWrite.hidden = true;
}

function formatDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : dateFormat.format(date);
}

function renderPosts(data) {
  const fragment = document.createDocumentFragment();
  data.content.forEach((post, index) => {
    const row = element('tr');
    const ordinal = data.totalElements - data.page * data.size - index;
    row.append(element('td', 'post-number', numberFormat.format(ordinal)));
    const titleCell = element('td', 'post-title');
    const title = element('a', 'post-title-button', post.title);
    const detailParams = new URLSearchParams(location.search);
    detailParams.set('id', post.id);
    title.href = `/post.html?${detailParams}`;
    title.title = post.title;
    const mobileMeta = element('span', 'post-mobile-meta');
    mobileMeta.append(element('span', 'author-name', post.author.nickname), element('span', '', '·'), element('span', '', formatDate(post.createdAt)));
    titleCell.append(title, mobileMeta);
    const author = element('td', 'post-author');
    const authorName = element('span', 'author-name', post.author.nickname);
    authorName.title = post.author.nickname;
    author.append(authorName);
    row.append(titleCell, author, element('td', 'post-date', formatDate(post.createdAt)));
    fragment.append(row);
  });
  list.replaceChildren(fragment);
  statePanel.hidden = true;
}

function goToPage(page) {
  state.page = page;
  syncUrl();
  loadPosts(true);
}

function pageButton(label, page, accessibleLabel, disabled = false) {
  const button = element('button', 'page-button', label);
  button.type = 'button';
  button.disabled = disabled;
  button.setAttribute('aria-label', accessibleLabel);
  button.addEventListener('click', () => goToPage(page));
  return button;
}

function renderPagination(data, focusPage) {
  pager.replaceChildren();
  paginationArea.hidden = data.totalPages === 0;
  if (!data.totalPages) return;
  document.getElementById('page-summary').textContent = `${numberFormat.format(data.page + 1)} / ${numberFormat.format(data.totalPages)} 페이지`;
  pager.append(pageButton('‹', data.page - 1, '이전 페이지', data.first));
  const pages = new Set([0, data.totalPages - 1]);
  const start = Math.max(0, Math.min(data.page - 1, data.totalPages - 3));
  for (let page = start; page < Math.min(data.totalPages, start + 3); page++) pages.add(page);
  let previous = -1;
  [...pages].sort((a, b) => a - b).forEach(page => {
    if (page - previous > 1) { const gap = element('span', 'page-gap', '…'); gap.setAttribute('aria-hidden', 'true'); pager.append(gap); }
    const button = pageButton(String(page + 1), page, `${page + 1}페이지`);
    if (page === data.page) button.setAttribute('aria-current', 'page');
    pager.append(button);
    previous = page;
  });
  pager.append(pageButton('›', data.page + 1, '다음 페이지', data.last));
  if (focusPage) pager.querySelector('[aria-current="page"]')?.focus({ preventScroll: true });
}

async function loadPosts(focusPage = false) {
  currentRequest?.abort();
  const controller = new AbortController();
  currentRequest = controller;
  const timeout = setTimeout(() => controller.abort('timeout'), 15000);
  search.value = state.keyword;
  sizeSelect.value = String(state.size);
  results.setAttribute('aria-busy', 'true');
  paginationArea.hidden = true;
  count.textContent = '—';
  summary.textContent = '이야기를 불러오고 있어요.';
  showState('게시글을 불러오고 있어요', '잠시만 기다려 주세요.');
  try {
    const params = new URLSearchParams({ page: state.page, size: state.size });
    if (state.keyword) params.set('keyword', state.keyword);
    const response = await fetch(`/api/posts?${params}`, { signal: controller.signal, credentials: 'same-origin', headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error('게시글 조회 실패');
    const data = await response.json();
    if (controller !== currentRequest) return;
    if (state.page > 0 && (data.totalPages === 0 || state.page >= data.totalPages)) {
      state.page = Math.max(0, data.totalPages - 1);
      syncUrl(true);
      return loadPosts(focusPage);
    }
    count.textContent = numberFormat.format(data.totalElements);
    summary.textContent = state.keyword
      ? `“${state.keyword}” 검색 결과 ${numberFormat.format(data.totalElements)}개`
      : `총 ${numberFormat.format(data.totalElements)}개의 이야기가 있어요.`;
    if (data.content.length) {
      renderPosts(data);
    } else if (state.keyword) {
      showState('검색 결과가 없어요', '다른 검색어로 찾아보거나 전체 글을 확인해 보세요.', '전체 글 보기', () => {
        state.keyword = '';
        state.page = 0;
        syncUrl();
        loadPosts();
        search.focus();
      });
    } else {
      showState('아직 등록된 글이 없어요', '가장 먼저 새로운 이야기를 나눠보세요.');
      stateWrite.href = writeUrl();
      stateWrite.hidden = false;
    }
    renderPagination(data, focusPage);
  } catch (error) {
    if (controller !== currentRequest) return;
    summary.textContent = '게시글을 불러오지 못했어요.';
    showState('잠시 연결이 원활하지 않아요', '연결 상태를 확인한 뒤 다시 시도해 주세요.', '다시 시도', () => loadPosts());
  } finally {
    clearTimeout(timeout);
    if (controller === currentRequest) results.setAttribute('aria-busy', 'false');
  }
}

form.addEventListener('submit', event => {
  event.preventDefault();
  state.keyword = search.value.trim().slice(0, 100);
  state.page = 0;
  syncUrl();
  loadPosts();
});
sizeSelect.addEventListener('change', () => {
  state.size = Number(sizeSelect.value);
  state.page = 0;
  syncUrl();
  loadPosts();
});
window.addEventListener('popstate', () => { state = readState(); updateNavigationLinks(); loadPosts(); });
window.addEventListener('focus', () => loadSession());
window.addEventListener('pageshow', event => { if (event.persisted) loadSession(); });
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') loadSession(); });
state = readState();
syncUrl(true);
loadPosts();
loadSession();
