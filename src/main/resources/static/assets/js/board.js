import { announcePending, element, setupPendingActions } from './ui.js';

const form = document.getElementById('search-form');
const search = document.getElementById('search');
const sizeSelect = document.getElementById('page-size');
const results = document.getElementById('results');
const list = document.getElementById('post-list');
const statePanel = document.getElementById('list-state');
const stateAction = document.getElementById('state-action');
const summary = document.getElementById('list-summary');
const count = document.getElementById('post-count');
const pager = document.getElementById('pagination');
const paginationArea = document.getElementById('pagination-area');
const numberFormat = new Intl.NumberFormat('ko-KR');
const dateFormat = new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' });
let state;
let currentRequest;

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
}

function showState(title, description, actionLabel, action) {
  list.replaceChildren();
  statePanel.hidden = false;
  document.getElementById('state-title').textContent = title;
  document.getElementById('state-description').textContent = description;
  stateAction.hidden = !actionLabel;
  stateAction.textContent = actionLabel || '';
  stateAction.onclick = action || null;
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
      showState('아직 등록된 글이 없어요', '가장 먼저 새로운 이야기를 나눠보세요.', '첫 글 쓰기', () => announcePending('게시글 작성'));
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
window.addEventListener('popstate', () => { state = readState(); loadPosts(); });
setupPendingActions();
state = readState();
syncUrl(true);
loadPosts();
