// Run: node --experimental-vm-modules src/test/js/post.test.mjs
// Executes the real post/navigation/UI modules with a small DOM and an API double.
// Browser layout, native picker/constraint validation, and BFCache eligibility are not simulated.
import assert from 'node:assert/strict';
import { Blob, File } from 'node:buffer';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers';
import { createContext, SourceTextModule, SyntheticModule } from 'node:vm';

const staticRoot = new URL('../../main/resources/static/', import.meta.url);
const html = await readFile(new URL('post.html', staticRoot), 'utf8');
const sources = {};
for (const name of ['post.js', 'navigation.js', 'ui.js']) {
  sources[name] = await readFile(new URL('assets/js/' + name, staticRoot), 'utf8');
}

const NativeFormData = globalThis.FormData ?? (await new Response('', {
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
}).formData()).constructor;
const flush = () => new Promise(resolve => setImmediate(resolve));
const authenticated = { id: 1, nickname: '작성자' };
const initialPost = { id: 7, title: '원래 제목', content: '원래 본문', author: authenticated, createdAt: '2026-09-08T12:00:00', updatedAt: '2026-09-08T12:00:00' };
const initialFiles = [{ id: 11, originalFilename: '기존 한글.txt', size: 8, contentType: 'text/plain', downloadUrl: '/api/files/11/download' }];
const attachment = (name = '한글.txt', bytes = '첨부 내용') => new File([bytes], name, { type: 'text/plain' });
function deferred() {
  let resolve, reject;
  const promise = new Promise((accept, decline) => { resolve = accept; reject = decline; });
  return { promise, resolve, reject };
}
class MockApiError extends Error {
  constructor(message, status = 0, mayHaveSucceeded = false) {
    super(message); this.status = status; this.mayHaveSucceeded = mayHaveSucceeded;
  }
}

async function harness({ session = async () => authenticated, loadPost = async () => ({ ...initialPost }), loadFiles = async () => initialFiles.map(file => ({ ...file })), mutate = async () => ({ ...initialPost }), query = '?id=7' } = {}) {
  const ids = new Map();
  const requests = [], redirects = [], storageWrites = [];
  const events = new Map(), documentEvents = new Map();
  const document = { activeElement: null, visibilityState: 'visible' };
  const matches = (node, selector) => {
    const parts = selector.trim().split(/\s+/);
    const leaf = parts.pop();
    const ownMatch = leaf.startsWith('[') ? node.attributes.has(leaf.slice(1, -1))
      : leaf.startsWith('.') ? node.className.split(/\s+/).includes(leaf.slice(1)) : node.tagName === leaf;
    if (!ownMatch) return false;
    if (!parts.length) return true;
    for (let parent = node.parentNode; parent; parent = parent.parentNode) {
      if (matches(parent, parts.join(' '))) return true;
    }
    return false;
  };
  function node(tagName, attributes = new Map()) {
    const item = {
      tagName, attributes, children: [], parentNode: null, listeners: new Map(),
      dataset: new Proxy({}, {
        get(target, key) { return attributes.get('data-' + String(key).replace(/[A-Z]/g, letter => '-' + letter.toLowerCase())); },
        set(target, key, value) { attributes.set('data-' + String(key).replace(/[A-Z]/g, letter => '-' + letter.toLowerCase()), String(value)); return true; }
      }),
      value: '', files: [], className: attributes.get('class') ?? '',
      hidden: attributes.has('hidden'), disabled: attributes.has('disabled'),
      get textContent() { return this.children.length ? this.children.map(child => child.textContent).join('') : this.text ?? ''; },
      set textContent(value) { this.replaceChildren(); this.text = String(value); },
      set innerHTML(value) { assert.fail(`Unexpected HTML injection into ${this.tagName}`); },
      get href() { return this.attributes.get('href'); },
      set href(value) { this.attributes.set('href', String(value)); },
      get isConnected() { return this === document.body || Boolean(this.parentNode?.isConnected); },
      getAttribute(name) { return this.attributes.get(name); },
      setAttribute(name, value) { this.attributes.set(name, String(value)); },
      removeAttribute(name) { this.attributes.delete(name); },
      append(...children) { for (const child of children) { child.parentNode = this; this.children.push(child); } },
      replaceChildren(...children) { for (const child of this.children) child.parentNode = null; this.children = []; this.text = ''; this.append(...children); },
      remove() { this.parentNode.children = this.parentNode.children.filter(child => child !== this); this.parentNode = null; },
      querySelectorAll(selector) {
        const result = [];
        for (const child of this.children) {
          if (selector.split(',').some(part => matches(child, part))) result.push(child);
          result.push(...child.querySelectorAll(selector));
        }
        return result;
      },
      querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; },
      closest(selector) {
        for (let item = this; item; item = item.parentNode) {
          if (selector.split(',').some(part => matches(item, part))) return item;
        }
        return null;
      },
      addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(listener);
      },
      focus() { document.activeElement = this; },
      showModal() { this.open = true; },
      close(value) {
        this.open = false; this.returnValue = value;
        for (const listener of this.listeners.get('close') ?? []) listener({ target: this });
      }
    };
    const classes = new Set(item.className.split(/\s+/));
    item.classList = {
      toggle(name, on) { if (on) classes.add(name); else classes.delete(name); },
      add: name => classes.add(name), remove: name => classes.delete(name),
      contains: name => classes.has(name)
    };
    return item;
  }
  const root = node('document');
  const stack = [root];
  const voidTags = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
  for (const match of html.matchAll(/<(\/)?([a-z][a-z0-9-]*)\b([^>]*)>/gi)) {
    const tag = match[2].toLowerCase();
    if (match[1]) {
      while (stack.length > 1) { if (stack.pop().tagName === tag) break; }
      continue;
    }
    const attributes = new Map([...match[3].matchAll(/([a-z][a-z0-9-]*)(?:="([^"]*)")?/gi)]
      .map(attribute => [attribute[1], attribute[2] ?? '']));
    const item = node(tag, attributes);
    if (attributes.has('id')) { item.id = attributes.get('id'); ids.set(item.id, item); }
    stack.at(-1).append(item);
    if (tag === 'body') document.body = item;
    if (!voidTags.has(tag)) stack.push(item);
  }
  document.createElement = tag => node(tag);
  document.getElementById = id => { assert.ok(ids.has(id), `post.html is missing #${id}`); return ids.get(id); };
  document.querySelectorAll = selector => root.querySelectorAll(selector);
  document.addEventListener = (type, listener) => {
    if (!documentEvents.has(type)) documentEvents.set(type, []);
    documentEvents.get(type).push(listener);
  };
  const storage = { getItem: () => null, setItem: (...args) => storageWrites.push(args), removeItem() {} };
  const context = createContext({
    document, URL, URLSearchParams, AbortController, FormData: NativeFormData, Blob,
    window: { addEventListener(type, listener) {
      if (!events.has(type)) events.set(type, []);
      events.get(type).push(listener);
    } },
    localStorage: storage, sessionStorage: storage,
    location: { pathname: '/post.html', search: query, hash: '',
      replace: path => redirects.push({ method: 'replace', path }), assign: path => redirects.push({ method: 'assign', path }) },
    setTimeout: () => 1, clearTimeout() {},
    fetch() { throw new Error('Post tests must not use the network'); }
  });
  const api = new SyntheticModule(['ApiError', 'request'], function () {
    this.setExport('ApiError', MockApiError);
    this.setExport('request', async (path, options = {}) => {
      const call = { path, options }; requests.push(call);
      if (path === '/api/auth/me') return session(call);
      if ((options.method ?? 'GET') === 'GET') {
        if (path.includes('/comments?')) return { content: [], totalElements: 0, totalPages: 0, page: 0, size: 10, first: true, last: true };
        return path.endsWith('/files') ? loadFiles(call) : loadPost(call);
      }
      return mutate(path, options);
    });
  }, { context });
  const modules = { 'api.js': api };
  for (const [name, source] of Object.entries(sources)) modules[name] = new SourceTextModule(source, { context, identifier: name });
  await modules['post.js'].link(specifier => {
    const module = modules[specifier.replace('./', '')];
    assert.ok(module, `Unexpected import: ${specifier}`); return module;
  });
  await modules['post.js'].evaluate();
  await flush();
  const event = extra => ({ defaultPrevented: false, button: 0,
    preventDefault() { this.defaultPrevented = true; }, ...extra });
  const emit = async (target, type, extra = {}) => {
    const instance = event({ target, currentTarget: target, ...extra });
    for (const listener of target.listeners.get(type) ?? []) await listener(instance);
    await flush();
    return instance;
  };
  return {
    node: id => document.getElementById(id), document, requests, redirects, storageWrites,
    get mutations() { return requests.filter(call => !['GET', 'HEAD'].includes(call.options.method ?? 'GET')); },
    get fileNames() { return ids.get('file-list').children.map(item => item.children[0].textContent); },
    links: kind => document.querySelectorAll(`[data-${kind}-link]`),
    fill(title = '제목', content = '본문') { ids.get('post-title').value = title; ids.get('post-content').value = content; },
    submit: () => emit(ids.get('comment-form'), 'submit'),
    click: (id, extra) => emit(typeof id === 'string' ? ids.get(id) : id, 'click', extra),
    input: id => emit(ids.get(id), 'input'),
    async select(files) { ids.get('file-input').files = files; ids.get('file-input').value = 'C:\\fakepath\\selected'; return emit(ids.get('file-input'), 'change'); },
    remove: index => emit(ids.get('file-list').children[index].children[2], 'click'),
    existingButton: index => ids.get('existing-file-list').children[index].querySelector('button'),
    async confirm(accepted) {
      await flush();
      const dialog = document.body.querySelectorAll('dialog').at(-1);
      assert.ok(dialog?.open, 'Expected a confirmation dialog');
      await emit(dialog.querySelectorAll('button')[accepted ? 1 : 0], 'click');
    },
    async windowEvent(type, extra = {}) {
      const instance = event(extra);
      for (const listener of events.get(type) ?? []) await listener(instance);
      await flush(); return instance;
    },
    async visibility(value) {
      document.visibilityState = value;
      for (const listener of documentEvents.get('visibilitychange') ?? []) await listener({});
      await flush();
    }
  };
}
const checks = [];
async function check(name, run) { await run(); checks.push(name); }

await check('The author receives a real edit link preserving only normalized list parameters', async () => {
  const page = await harness({ query: '?id=7&keyword=Spring&page=2&size=50&unknown=discard' });
  assert.equal(page.node('post-edit').tagName, 'a');
  assert.equal(page.node('post-edit').href, '/edit.html?id=7&keyword=Spring&page=2&size=50');
  assert.equal(page.node('post-edit').getAttribute('aria-disabled'), 'false');
  assert.equal(page.node('post-owner-actions').hidden, false);
  assert.equal((await page.click('post-edit')).defaultPrevented, false);
  assert.equal(page.redirects.length, 0);
  assert.equal(page.mutations.length, 0);
});

await check('Guests and other accounts cannot open the author edit link', async () => {
  for (const account of [null, { id: 2, nickname: '다른 계정' }]) {
    const page = await harness({ session: async () => {
      if (!account) throw new MockApiError('로그인 필요', 401);
      return account;
    } });
    assert.equal(page.node('post-owner-actions').hidden, true);
    assert.equal(page.node('post-edit').href, undefined);
    assert.equal(page.node('post-edit').getAttribute('aria-disabled'), 'true');
    assert.equal((await page.click('post-edit')).defaultPrevented, true);
    assert.equal(page.redirects.length, 0);
  }
});

await check('Comment drafts survive cancelled navigation and duplicate prompts, then leave only on confirmation', async () => {
  const page = await harness({ query: '?id=7&keyword=Spring&page=2&size=10' });
  page.node('comment-content').value = '저장하지 않은 댓글';
  await page.input('comment-content');
  for (const extra of [{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { button: 1 }]) {
    assert.equal((await page.click('post-edit', extra)).defaultPrevented, false);
    assert.equal(page.document.body.querySelectorAll('dialog').length, 0);
  }
  const cancelled = page.click('post-edit'); await flush();
  assert.equal(page.node('post-edit').href, undefined);
  assert.equal((await page.click('post-edit')).defaultPrevented, true);
  assert.equal(page.document.body.querySelectorAll('dialog').length, 1);
  await page.confirm(false); await cancelled;
  assert.equal(page.redirects.length, 0);
  assert.equal(page.node('comment-content').value, '저장하지 않은 댓글');
  assert.equal(page.node('post-edit').getAttribute('aria-disabled'), 'false');
  const confirmed = page.click('post-edit'); await page.confirm(true); await confirmed;
  assert.deepEqual(page.redirects, [{ method: 'assign', path: '/edit.html?id=7&keyword=Spring&page=2&size=10' }]);
  assert.equal(page.mutations.length, 0);
});

await check('Preview never calls the API and keeps editing disabled even after focus and click events', async () => {
  const page = await harness({ query: '?preview=1&id=7' });
  assert.equal(page.requests.length, 0);
  assert.equal(page.node('post-view').hidden, false);
  assert.ok(page.node('post-title').textContent.length > 0);
  assert.equal(page.node('comment-list').children.length, 2);
  assert.equal(page.node('post-edit').href, undefined);
  assert.equal(page.node('post-edit').getAttribute('aria-disabled'), 'true');
  assert.equal((await page.click('post-edit')).defaultPrevented, true);
  await page.windowEvent('focus'); await page.windowEvent('pageshow', { persisted: true });
  assert.equal(page.requests.length, 0);
  assert.equal(page.redirects.length, 0);
});

export const result = { passed: checks.length, checks };
console.log(`post: ${checks.length} behavior checks passed`);
