// Run: node --experimental-vm-modules src/test/js/write.test.mjs
// Executes the real writer/navigation/UI modules with a small DOM and an API double.
// Browser layout, native picker/constraint validation, and BFCache eligibility are not simulated.
import assert from 'node:assert/strict';
import { Blob, File } from 'node:buffer';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers';
import { createContext, SourceTextModule, SyntheticModule } from 'node:vm';

const staticRoot = new URL('../../main/resources/static/', import.meta.url);
const html = await readFile(new URL('write.html', staticRoot), 'utf8');
const sources = {};
for (const name of ['write.js', 'navigation.js', 'ui.js', 'editor-fields.js']) {
  sources[name] = await readFile(new URL('assets/js/' + name, staticRoot), 'utf8');
}
const NativeFormData = globalThis.FormData ?? (await new Response('', {
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
}).formData()).constructor;
const flush = () => new Promise(resolve => setImmediate(resolve));
const authenticated = { id: 1, nickname: '작성자' };
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

async function harness({ session = async () => authenticated, mutate = async () => ({ id: 7 }), query = '' } = {}) {
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
      tagName, attributes, children: [], parentNode: null, listeners: new Map(), dataset: {},
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
  document.getElementById = id => { assert.ok(ids.has(id), `write.html is missing #${id}`); return ids.get(id); };
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
    location: { pathname: '/write.html', search: query, hash: '',
      replace: path => redirects.push({ method: 'replace', path }), assign: path => redirects.push({ method: 'assign', path }) },
    setTimeout: () => 1, clearTimeout() {},
    fetch() { throw new Error('Writer tests must not use the network'); }
  });
  const api = new SyntheticModule(['ApiError', 'request'], function () {
    this.setExport('ApiError', MockApiError);
    this.setExport('request', async (path, options = {}) => {
      const call = { path, options }; requests.push(call);
      return path === '/api/auth/me' ? session(call) : mutate(path, options);
    });
  }, { context });
  const modules = { 'api.js': api };
  for (const [name, source] of Object.entries(sources)) modules[name] = new SourceTextModule(source, { context, identifier: name });
  await modules['write.js'].link(specifier => {
    const module = modules[specifier.replace('./', '')];
    assert.ok(module, `Unexpected import: ${specifier}`); return module;
  });
  await modules['write.js'].evaluate();
  await flush();
  const event = extra => ({ defaultPrevented: false, button: 0,
    preventDefault() { this.defaultPrevented = true; }, ...extra });
  const emit = async (target, type, extra = {}) => {
    const instance = event(extra);
    for (const listener of target.listeners.get(type) ?? []) await listener(instance);
    await flush();
    return instance;
  };
  return {
    node: id => document.getElementById(id), document, requests, redirects, storageWrites,
    get mutations() { return requests.filter(call => call.path !== '/api/auth/me'); },
    get fileNames() { return ids.get('file-list').children.map(item => item.children[0].textContent); },
    links: kind => document.querySelectorAll(`[data-${kind}-link]`),
    fill(title = '제목', content = '본문') { ids.get('post-title').value = title; ids.get('post-content').value = content; },
    submit: () => emit(ids.get('post-form'), 'submit'),
    click: (id, extra) => emit(typeof id === 'string' ? ids.get(id) : id, 'click', extra),
    input: id => emit(ids.get(id), 'input'),
    async select(files) { ids.get('file-input').files = files; ids.get('file-input').value = 'C:\\fakepath\\selected'; return emit(ids.get('file-input'), 'change'); },
    remove: index => emit(ids.get('file-list').children[index].children[2], 'click'),
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

await check('Guests can draft and open safe login/signup return links without losing the draft', async () => {
  const page = await harness({ session: async () => { throw new MockApiError('로그인 필요', 401); }, query: '?keyword=Spring&page=2&size=50&preview=1&returnTo=https://evil.example/' });
  assert.equal(page.node('post-fields').disabled, false);
  assert.equal(page.node('post-submit').disabled, true);
  assert.equal(page.node('guest-notice').hidden, false);
  for (const kind of ['login', 'signup']) for (const link of page.links(kind)) {
    const url = new URL(link.href, 'https://board.invalid');
    assert.equal(url.pathname, `/${kind}.html`);
    assert.equal(url.searchParams.get('returnTo'), '/write.html?keyword=Spring&page=2&size=50');
    assert.equal(link.getAttribute('target'), '_blank');
    assert.equal(link.getAttribute('rel'), 'noopener');
  }
  page.fill(); await page.select([attachment()]); await page.submit();
  assert.equal(page.mutations.length, 0);
  assert.equal(page.node('post-title').value, '제목');
  assert.deepEqual(page.fileNames, ['한글.txt']);
});

await check('Whitespace-only and oversized text fail locally; counters and correction feedback update', async () => {
  for (const [title, content, id] of [[' \t', '본문', 'post-title'], ['제목', '\n \t', 'post-content'], ['t'.repeat(201), '본문', 'post-title'], ['제목', 'c'.repeat(10001), 'post-content']]) {
    const page = await harness(); page.fill(title, content); await page.input(id); await page.submit();
    assert.equal(page.mutations.length, 0);
    assert.equal(page.requests.length, 1);
    assert.equal(page.node(id).getAttribute('aria-invalid'), 'true');
    assert.equal(page.document.activeElement, page.node(id));
    assert.equal(page.node(id).value, id === 'post-title' ? title : content);
    page.fill(); await page.input(id);
    assert.equal(page.node(id).getAttribute('aria-invalid'), 'false');
  }
  const page = await harness(); page.fill('t'.repeat(201), 'c'.repeat(10001));
  await page.input('post-title');
  assert.equal(page.node('title-count').textContent, '201 / 200');
  assert.equal(page.node('content-count').textContent, '10,001 / 10,000');
  assert.equal(page.node('title-count').classList.contains('is-invalid'), true);
  assert.equal(page.node('post-title').getAttribute('maxlength'), undefined);
  assert.equal(page.node('post-content').getAttribute('maxlength'), undefined);
  page.fill('', ''); await page.submit();
  page.node('post-content').focus(); page.node('post-content').value = '작성 중';
  await page.input('post-content');
  assert.equal(page.document.activeElement, page.node('post-content'));
});

await check('Length boundaries and surrounding whitespace are transmitted without trimming or HTML interpretation', async () => {
  for (const [title, content] of [['t'.repeat(200), 'c'.repeat(10000)], ['  제목 🧪  ', '\n <script>alert(1)</script>\n 본문  ']]) {
    const page = await harness(); page.fill(title, content); await page.submit();
    const body = page.mutations[0].options.body;
    assert.ok(body instanceof NativeFormData);
    assert.equal(body.get('post').type, 'application/json');
    assert.deepEqual(JSON.parse(await body.get('post').text()), { title, content });
    assert.deepEqual([...body.keys()], ['post']);
    assert.equal(page.storageWrites.length, 0);
  }
});

await check('Multiple file selections accumulate, removal keeps order and unsafe-looking names remain text', async () => {
  const page = await harness();
  await page.select([attachment('<img onerror=alert(1)>.txt'), attachment('two.txt')]);
  await page.select([attachment('three.txt')]);
  assert.deepEqual(page.fileNames, ['<img onerror=alert(1)>.txt', 'two.txt', 'three.txt']);
  assert.equal(page.node('file-list').children[0].children[0].children.length, 0);
  assert.equal(page.node('file-input').value, '');
  await page.remove(1);
  assert.deepEqual(page.fileNames, ['<img onerror=alert(1)>.txt', 'three.txt']);
  assert.equal(page.document.activeElement, page.node('file-list').children[1].children[2]);
  await page.remove(1); await page.remove(0);
  assert.equal(page.node('file-list').hidden, true);
  assert.equal(page.document.activeElement, page.node('file-input'));
});

await check('Rejected file batches preserve the previous selection for count, size and name violations', async () => {
  const page = await harness(); await page.select([attachment('kept.txt')]);
  const invalid = [
    [attachment('empty.txt', '')], [{ name: 'huge.bin', size: 10 * 1024 * 1024 + 1 }],
    Array.from({ length: 5 }, (_, index) => attachment(`${index}.txt`)),
    ...['../path.txt', 'C:\\path.txt', 'bad\nname.txt', 'bad\u007fname', '..', ' ', 'a'.repeat(256)].map(name => [attachment(name)])
  ];
  for (const candidate of invalid) {
    await page.select(candidate);
    assert.deepEqual(page.fileNames, ['kept.txt']);
    assert.equal(page.node('file-error').hidden, false);
    assert.equal(page.node('file-input').value, '');
  }
  await page.select([attachment('new.txt')]);
  assert.deepEqual(page.fileNames, ['kept.txt', 'new.txt']);
  assert.equal(page.node('file-error').hidden, true);
  const large = await harness();
  await large.select(Array.from({ length: 4 }, (_, index) => ({ name: `${index}.bin`, size: 10 * 1024 * 1024 })));
  assert.equal(large.fileNames.length, 4);
  await large.select([{ name: 'fifth.bin', size: 10 * 1024 * 1024 }]);
  assert.equal(large.fileNames.length, 4);
  assert.ok(large.node('file-error').textContent.includes('50 MiB'));
});

await check('A single multipart creation sends JSON plus exact file bytes and returns to a filtered detail page', async () => {
  const page = await harness({ query: '?keyword=%20Spring%20&page=3&size=10&preview=1&id=99', mutate: async () => ({ id: 42 }) });
  page.fill('  제목  ', '\n본문 🧪\n');
  const bytes = new Uint8Array([0, 1, 127, 128, 255]);
  await page.select([attachment('자료.bin', bytes)]); await page.select([attachment('two.txt', 'Second')]);
  await page.submit();
  assert.deepEqual(page.requests.map(call => call.path), ['/api/auth/me', '/api/auth/me', '/api/posts']);
  const call = page.mutations[0];
  assert.equal(call.options.method, 'POST');
  assert.deepEqual([...call.options.body.keys()], ['post', 'files', 'files']);
  assert.deepEqual(JSON.parse(await call.options.body.get('post').text()), { title: '  제목  ', content: '\n본문 🧪\n' });
  const uploaded = call.options.body.getAll('files');
  assert.deepEqual(Array.from(uploaded, file => file.name), ['자료.bin', 'two.txt']);
  assert.deepEqual(new Uint8Array(await uploaded[0].arrayBuffer()), bytes);
  assert.equal(await uploaded[1].text(), 'Second');
  assert.deepEqual(page.redirects, [{ method: 'replace', path: '/post.html?id=42&keyword=Spring&page=3&size=10' }]);
  assert.equal((await page.windowEvent('beforeunload')).defaultPrevented, false);
});

await check('Duplicate submits are blocked throughout session preflight and the creation request', async () => {
  const preflight = deferred(), mutation = deferred(); let sessions = 0;
  const page = await harness({ session: () => ++sessions === 1 ? authenticated : preflight.promise, mutate: () => mutation.promise });
  page.fill(); await page.select([attachment()]);
  const first = page.submit(); await flush(); await page.submit();
  assert.equal(page.requests.length, 2);
  assert.equal(page.node('post-fields').disabled, true);
  assert.equal(page.node('post-form').getAttribute('aria-busy'), 'true');
  preflight.resolve(authenticated); await flush(); await page.submit();
  await page.select([attachment('ignored.txt')]); await page.remove(0);
  await page.windowEvent('focus');
  assert.deepEqual(page.fileNames, ['한글.txt']);
  assert.equal(page.mutations.length, 1);
  mutation.resolve({ id: 7 }); await first;
  assert.equal(page.redirects.length, 1);
});

await check('Expired preflight sessions prevent creation while keeping all input and selected files', async () => {
  let current = authenticated;
  const page = await harness({ session: async () => { if (current) return current; throw new MockApiError('Login required', 401); } });
  page.fill(); await page.select([attachment()]); current = null; await page.submit();
  assert.equal(page.mutations.length, 0);
  assert.equal(page.node('post-submit').disabled, true);
  assert.equal(page.node('post-fields').disabled, false);
  assert.deepEqual(page.fileNames, ['한글.txt']);
  assert.equal(page.node('post-content').value, '본문');
  current = authenticated; await page.click('refresh-session');
  assert.equal(page.node('post-submit').disabled, false);
  assert.equal(page.node('guest-notice').hidden, true);
  assert.equal(page.mutations.length, 0);
});

await check('401 and 413 creation failures retain draft/files and expose useful recovery without automatic retry', async () => {
  for (const status of [401, 413]) {
    const page = await harness({ mutate: async () => { throw new MockApiError('Server message', status); } });
    page.fill(); await page.select([attachment()]); await page.submit();
    assert.equal(page.mutations.length, 1);
    assert.equal(page.node('post-title').value, '제목');
    assert.deepEqual(page.fileNames, ['한글.txt']);
    assert.equal(page.node('post-fields').disabled, false);
    assert.equal(page.node('uncertain-panel').hidden, true);
    assert.equal(page.redirects.length, 0);
    if (status === 401) {
      assert.equal(page.node('post-submit').disabled, true);
      assert.equal(page.node('guest-notice').hidden, false);
    } else {
      assert.ok(page.node('submit-error').textContent.includes('첨부파일을 줄여'));
      await page.remove(0); assert.equal(page.fileNames.length, 0);
    }
  }
});

await check('Uncertain results freeze retries until explicit confirmation while preserving every draft field', async () => {
  let attempts = 0;
  const page = await harness({ mutate: async () => { if (++attempts === 1) throw new MockApiError('Lost response', 0, true); return { id: 8 }; } });
  page.fill(); await page.select([attachment()]); await page.submit();
  assert.equal(page.node('uncertain-panel').hidden, false);
  assert.equal(page.node('post-fields').disabled, true);
  assert.equal(page.node('check-posts-link').href, '/');
  assert.equal(page.node('check-posts-link').getAttribute('target'), '_blank');
  assert.equal(page.node('check-posts-link').getAttribute('rel'), 'noopener');
  await page.submit(); await page.remove(0); await page.windowEvent('focus');
  assert.equal(page.mutations.length, 1);
  assert.deepEqual(page.fileNames, ['한글.txt']);
  const cancelled = page.click('allow-retry'); await page.confirm(false); await cancelled;
  assert.equal(page.node('post-fields').disabled, true);
  const resumed = page.click('allow-retry'); await page.confirm(true); await resumed;
  assert.equal(page.node('post-fields').disabled, false);
  assert.equal(page.mutations.length, 1);
  assert.equal(page.node('post-title').value, '제목');
  assert.deepEqual(page.fileNames, ['한글.txt']);
  await page.submit();
  assert.equal(page.mutations.length, 2);
  assert.deepEqual(page.redirects, [{ method: 'replace', path: '/post.html?id=8' }]);
});

await check('A definite pre-send failure stays editable and malformed success identifiers are treated as uncertain', async () => {
  const unsent = await harness({ mutate: async () => { throw new MockApiError('CSRF unavailable', 503, false); } });
  unsent.fill(); await unsent.submit();
  assert.equal(unsent.node('post-fields').disabled, false);
  assert.equal(unsent.node('uncertain-panel').hidden, true);
  assert.equal(unsent.mutations.length, 1);
  for (const id of [undefined, 0, 'https://evil.example', '9223372036854775808']) {
    const page = await harness({ mutate: async () => ({ id }) }); page.fill(); await page.submit();
    assert.equal(page.node('uncertain-panel').hidden, false);
    assert.equal(page.redirects.length, 0);
    await page.submit(); assert.equal(page.mutations.length, 1);
  }
});

await check('Session refresh, focus and visibility changes preserve the draft and attachments', async () => {
  let current = authenticated;
  const page = await harness({ session: async () => current }); page.fill(); await page.select([attachment()]);
  current = { id: 2, nickname: '<img onerror=alert(1)>' }; await page.windowEvent('focus');
  assert.equal(page.node('session-name').textContent, '<img onerror=alert(1)>님');
  assert.equal(page.node('session-name').children.length, 0);
  const calls = page.requests.length; await page.visibility('hidden'); assert.equal(page.requests.length, calls);
  await page.visibility('visible'); assert.equal(page.requests.length, calls + 1);
  assert.equal(page.node('post-title').value, '제목');
  assert.deepEqual(page.fileNames, ['한글.txt']);
  assert.equal(page.mutations.length, 0);
});

await check('A superseded session lookup cannot overwrite a newer session', async () => {
  const initial = deferred(); let calls = 0;
  const page = await harness({ session: async () => ++calls === 1 ? initial.promise : authenticated });
  await page.windowEvent('focus');
  initial.resolve(null); await flush();
  assert.equal(page.requests[0].options.signal.aborted, true);
  assert.equal(page.node('post-submit').disabled, false);
  assert.equal(page.node('session-name').textContent, '작성자님');
});

await check('Logout keeps draft/files and a subsequent account refresh restores submission', async () => {
  let current = authenticated;
  const page = await harness({ session: async () => current, mutate: async path => {
    assert.equal(path, '/api/auth/logout'); current = null; return null;
  } });
  page.fill(); await page.select([attachment()]); await page.click('logout-button');
  assert.equal(page.mutations[0].options.method, 'POST');
  assert.equal(page.node('post-submit').disabled, true);
  assert.equal(page.node('post-title').value, '제목');
  assert.deepEqual(page.fileNames, ['한글.txt']);
  current = authenticated; await page.windowEvent('focus');
  assert.equal(page.node('post-submit').disabled, false);
  assert.equal(page.mutations.length, 1);
});

await check('Cancel navigation confirms draft loss, honors cancellation and preserves normalized list parameters', async () => {
  const page = await harness({ query: '?keyword=Spring&page=2&size=50&preview=1' });
  const cancel = page.links('list').at(-1);
  assert.equal((await page.click(cancel)).defaultPrevented, false);
  page.fill(); await page.select([attachment()]);
  assert.equal((await page.windowEvent('beforeunload')).defaultPrevented, true);
  assert.equal((await page.click(cancel, { ctrlKey: true })).defaultPrevented, false);
  const cancelled = page.click(cancel); await page.confirm(false);
  assert.equal((await cancelled).defaultPrevented, true);
  assert.equal(page.redirects.length, 0);
  assert.deepEqual(page.fileNames, ['한글.txt']);
  const confirmed = page.click(cancel); await page.confirm(true); await confirmed;
  assert.deepEqual(page.redirects, [{ method: 'assign', path: '/?keyword=Spring&page=2&size=50' }]);
  assert.equal((await page.windowEvent('beforeunload')).defaultPrevented, false);
});

await check('Cancel during creation is blocked and pagehide aborts a sent request without permitting silent retries', async () => {
  const page = await harness({ mutate: async (path, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(Object.assign(new Error('Aborted'), { name: 'AbortError', mayHaveSucceeded: true })), { once: true });
  }) });
  page.fill(); await page.select([attachment()]);
  const pending = page.submit(); await flush();
  const cancelled = await page.click(page.links('list').at(-1));
  assert.equal(cancelled.defaultPrevented, true);
  assert.equal(page.document.body.querySelectorAll('dialog').length, 0);
  assert.equal(page.node('toast').hidden, false);
  await page.windowEvent('pagehide'); await pending;
  assert.equal(page.mutations[0].options.signal.aborted, true);
  assert.equal(page.node('uncertain-panel').hidden, false);
  await page.windowEvent('pageshow', { persisted: true });
  assert.equal(page.node('post-fields').disabled, true);
  assert.deepEqual(page.fileNames, ['한글.txt']);
  assert.equal(page.node('post-content').value, '본문');
  await page.submit(); assert.equal(page.mutations.length, 1);
});

export const result = { passed: checks.length, checks };
console.log(`write: ${checks.length} behavior checks passed`);
