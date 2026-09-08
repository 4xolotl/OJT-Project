import { listUrl } from './ui.js';

// Rebuild known page URLs instead of trusting a caller-supplied navigation target.
export function safeReturnUrl(input) {
  if (typeof input !== 'string' || !input.startsWith('/') || input.startsWith('//')) return '/';
  let decoded;
  try { decoded = decodeURIComponent(input); }
  catch { return '/'; }
  if (/[\\\u0000-\u001f\u007f]/.test(input) || /[\\\u0000-\u001f\u007f]/.test(decoded)) return '/';

  const path = input.split(/[?#]/, 1)[0];
  if (!['/', '/index.html', '/post.html', '/write.html', '/edit.html'].includes(path)) return '/';
  const url = new URL(input, 'https://board.invalid');
  if (path === '/write.html') return writeUrl(url.search);
  const list = listUrl(url.search);
  if (path !== '/post.html' && path !== '/edit.html') return list;

  const id = url.searchParams.get('id') ?? '';
  if (!/^[1-9]\d{0,18}$/.test(id) || BigInt(id) > 9223372036854775807n) return '/';
  const params = new URLSearchParams({ id });
  const listParams = new URLSearchParams(list.includes('?') ? list.slice(list.indexOf('?') + 1) : '');
  for (const [name, value] of listParams) params.set(name, value);
  return `${path}?${params}${path === '/post.html' && url.hash === '#comments' ? '#comments' : ''}`;
}

export function loginUrl(returnTo = location.pathname + location.search + location.hash) {
  return `/login.html?returnTo=${encodeURIComponent(safeReturnUrl(returnTo))}`;
}

export function signupUrl(returnTo = location.pathname + location.search + location.hash) {
  return `/signup.html?returnTo=${encodeURIComponent(safeReturnUrl(returnTo))}`;
}

export function writeUrl(query = location.search) {
  return `/write.html${listUrl(query).slice(1)}`;
}

export function editUrl(postId, query = location.search) {
  if (!['string', 'number', 'bigint'].includes(typeof postId)
    || typeof postId === 'number' && !Number.isSafeInteger(postId)) return '/';
  const id = String(postId);
  if (!/^[1-9]\d{0,18}$/.test(id) || BigInt(id) > 9223372036854775807n) return '/';
  const list = listUrl(query);
  return `/edit.html?id=${id}${list.length > 1 ? '&' + list.slice(2) : ''}`;
}
