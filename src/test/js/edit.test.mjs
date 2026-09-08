// Run: node --experimental-vm-modules src/test/js/edit.test.mjs
// Executes the real editor/navigation/UI modules with a small DOM and an API double.
// Browser layout, native picker/constraint validation, and BFCache eligibility are not simulated.
import assert from 'node:assert/strict';
import { Blob, File } from 'node:buffer';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers';
import { createContext, SourceTextModule, SyntheticModule } from 'node:vm';

const staticRoot = new URL('../../main/resources/static/', import.meta.url);
const html = await readFile(new URL('edit.html', staticRoot), 'utf8');
const sources = {};
for (const name of ['edit.js', 'navigation.js', 'ui.js', 'editor-fields.js']) {
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
      get value() { return this.inputValue ?? ''; },
      set value(value) {
        const text = String(value);
        this.inputValue = tagName === 'textarea' ? text.replace(/\r\n?/g, '\n')
          : tagName === 'input' && (attributes.get('type') ?? 'text') === 'text' ? text.replace(/[\r\n]/g, '') : text;
      },
      files: [], className: attributes.get('class') ?? '',
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
  document.getElementById = id => { assert.ok(ids.has(id), `edit.html is missing #${id}`); return ids.get(id); };
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
    location: { pathname: '/edit.html', search: query, hash: '',
      replace: path => redirects.push({ method: 'replace', path }), assign: path => redirects.push({ method: 'assign', path }) },
    setTimeout: () => 1, clearTimeout() {},
    fetch() { throw new Error('Editor tests must not use the network'); }
  });
  const api = new SyntheticModule(['ApiError', 'request'], function () {
    this.setExport('ApiError', MockApiError);
    this.setExport('request', async (path, options = {}) => {
      const call = { path, options }; requests.push(call);
      if (path === '/api/auth/me') return session(call);
      if ((options.method ?? 'GET') === 'GET') return path.endsWith('/files') ? loadFiles(call) : loadPost(call);
      return mutate(path, options);
    });
  }, { context });
  const modules = { 'api.js': api };
  for (const [name, source] of Object.entries(sources)) modules[name] = new SourceTextModule(source, { context, identifier: name });
  await modules['edit.js'].link(specifier => {
    const module = modules[specifier.replace('./', '')];
    assert.ok(module, `Unexpected import: ${specifier}`); return module;
  });
  await modules['edit.js'].evaluate();
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
    submit: () => emit(ids.get('edit-form'), 'submit'),
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

await check('Invalid or missing identifiers never trigger API requests or enable saving', async () => {
  for (const query of ['', '?id=0', '?id=-1', '?id=1.5', '?id=9223372036854775808', '?id=https://evil.example/', '?id=7&preview=1', '?id=7&preview=0']) {
    const page = await harness({ query });
    assert.equal(page.requests.length, 0);
    assert.equal(page.node('post-submit').disabled, true);
    await page.submit(); assert.equal(page.mutations.length, 0);
  }
});

await check('An unchanged post is not sent again', async () => {
  const page = await harness();
  assert.equal(page.node('post-title').value, initialPost.title);
  assert.equal(page.node('post-content').value, initialPost.content);
  await page.submit();
  assert.equal(page.mutations.length, 0);
  assert.equal(page.redirects.length, 0);
});

await check('Whitespace-only and oversized text are rejected without mutating the original input', async () => {
  for (const [title, content, id] of [[' \t', '본문', 'post-title'], ['제목', '\n \t', 'post-content'], ['t'.repeat(201), '본문', 'post-title'], ['제목', 'c'.repeat(10001), 'post-content']]) {
    const page = await harness(); page.fill(title, content); await page.submit();
    assert.equal(page.mutations.length, 0);
    assert.equal(page.node(id).getAttribute('aria-invalid'), 'true');
    assert.equal(page.document.activeElement, page.node(id));
    assert.equal(page.node(id).value, id === 'post-title' ? title : content);
  }
});

await check('Guests and different accounts cannot submit changes', async () => {
  for (const account of [null, { id: 2, nickname: '다른 계정' }]) {
    const page = await harness({ session: async () => {
      if (!account) throw new MockApiError('로그인 필요', 401);
      return account;
    } });
    assert.equal(page.node('post-fields').disabled, true);
    assert.equal(page.node('post-submit').disabled, true);
    page.fill(); await page.submit();
    assert.equal(page.mutations.length, 0);
  }
});

await check('Saving stays locked until the post, attachment list and authenticated owner have all loaded', async () => {
  const files = deferred();
  const page = await harness({ loadFiles: () => files.promise });
  assert.equal(page.node('edit-form').hidden, true);
  assert.equal(page.node('post-fields').disabled, true);
  assert.equal(page.node('post-submit').disabled, true);
  await page.submit(); assert.equal(page.mutations.length, 0);
  files.resolve(initialFiles); await flush();
  assert.equal(page.node('edit-form').hidden, false);
  assert.equal(page.node('post-title').value, initialPost.title);
  assert.equal(page.node('post-fields').disabled, false);
  const account = deferred();
  const authenticating = await harness({ session: () => account.promise });
  assert.equal(authenticating.node('post-fields').disabled, true);
  account.resolve(authenticated); await flush();
  assert.equal(authenticating.node('post-fields').disabled, false);
  assert.equal(authenticating.node('post-submit').disabled, true);
  authenticating.fill(); await authenticating.input('post-title');
  assert.equal(authenticating.node('post-submit').disabled, false);
});

await check('Initial failures cannot save partial data; retryable errors reload both the post and files', async () => {
  for (const status of [404, 500]) {
    let failing = true;
    const page = await harness({ loadFiles: async () => {
      if (failing) throw new MockApiError('Load failed', status);
      return initialFiles;
    } });
    assert.equal(page.node('edit-form').hidden, true);
    assert.equal(page.node('post-submit').disabled, true);
    assert.equal(page.node('retry-load').hidden, status === 404);
    await page.submit(); assert.equal(page.mutations.length, 0);
    if (status === 500) {
      failing = false; await page.click('retry-load');
      assert.equal(page.requests.filter(call => call.path === '/api/posts/7').length, 2);
      assert.equal(page.requests.filter(call => call.path === '/api/posts/7/files').length, 2);
      assert.equal(page.node('edit-form').hidden, false);
      assert.equal(page.node('post-title').value, initialPost.title);
    }
  }
  for (const overrides of [
    { loadPost: async () => ({ ...initialPost, id: 8 }) },
    { loadPost: async () => ({ ...initialPost, updatedAt: 'invalid' }) },
    { loadFiles: async () => [initialFiles[0], initialFiles[0]] },
    { loadFiles: async () => [{ ...initialFiles[0], size: '8' }] }
  ]) {
    const page = await harness(overrides);
    assert.equal(page.node('edit-form').hidden, true);
    assert.equal(page.node('post-submit').disabled, true);
    assert.equal(page.mutations.length, 0);
  }
});

await check('Existing attachment deletion is reversible and reserved locally until save', async () => {
  const page = await harness({ loadFiles: async () => [{ ...initialFiles[0], originalFilename: '<img onerror=alert(1)>.txt', downloadUrl: 'https://evil.example/' }] });
  const link = page.node('existing-file-list').querySelector('a');
  assert.equal(link.textContent, '<img onerror=alert(1)>.txt');
  assert.equal(link.children.length, 0);
  assert.equal(link.href, '/api/files/11/download');
  await page.click(page.existingButton(0));
  assert.equal(page.existingButton(0).getAttribute('aria-pressed'), 'true');
  assert.ok(page.node('existing-file-count').textContent.includes('1개 삭제 예정'));
  assert.equal(page.node('post-submit').disabled, false);
  assert.equal(page.mutations.length, 0);
  await page.click(page.existingButton(0));
  assert.equal(page.existingButton(0).getAttribute('aria-pressed'), 'false');
  assert.equal(page.node('post-submit').disabled, true);
  await page.submit(); assert.equal(page.mutations.length, 0);
});

await check('Five new files are allowed in addition to existing attachments; rejected batches preserve previous choices', async () => {
  const existing = Array.from({ length: 6 }, (_, index) => ({ ...initialFiles[0], id: index + 11 }));
  const page = await harness({ loadFiles: async () => existing });
  await page.select([attachment('one.txt'), attachment('two.txt')]);
  await page.select([attachment('three.txt'), attachment('four.txt'), attachment('<img onerror=alert(1)>.txt')]);
  assert.equal(page.fileNames.length, 5);
  assert.equal(page.node('existing-file-list').children.length, 6);
  assert.equal(page.node('file-list').children[4].children[0].children.length, 0);
  await page.select([attachment('sixth.txt')]);
  assert.equal(page.fileNames.length, 5);
  assert.equal(page.node('file-error').hidden, false);
  await page.remove(4);
  assert.deepEqual(page.fileNames, ['one.txt', 'two.txt', 'three.txt', 'four.txt']);
  for (const file of [attachment('empty.txt', ''), { name: 'large.bin', size: 10 * 1024 * 1024 + 1 }, attachment('../path.txt'), attachment('bad\nname.txt'), attachment('a'.repeat(256))]) {
    await page.select([file]);
    assert.equal(page.fileNames.length, 4);
    assert.equal(page.node('file-error').hidden, false);
    assert.equal(page.node('file-input').value, '');
  }
  await page.remove(3);
  await page.select([attachment('valid.txt'), attachment('invalid.txt', '')]);
  assert.deepEqual(page.fileNames, ['one.txt', 'two.txt', 'three.txt']);
  const large = await harness();
  await large.select(Array.from({ length: 4 }, (_, index) => ({ name: `${index}.bin`, size: 10 * 1024 * 1024 })));
  assert.equal(large.fileNames.length, 4);
  await large.select([{ name: 'fifth.bin', size: 10 * 1024 * 1024 }]);
  assert.equal(large.fileNames.length, 4);
  assert.ok(large.node('file-error').textContent.includes('50 MiB'));
});

await check('One multipart PUT preserves raw text, snapshot version, original numeric file IDs and new file bytes', async () => {
  const page = await harness({ query: '?id=7&keyword=%20Spring%20&page=2&size=50&unknown=discard',
    loadFiles: async () => [initialFiles[0], { ...initialFiles[0], id: 12 }] });
  const title = '  수정 🧪 제목  ', content = '\n <script>alert(1)</script>\n 본문  ';
  page.fill(title, content);
  await page.click(page.existingButton(1));
  const bytes = new Uint8Array([0, 1, 127, 128, 255]);
  await page.select([attachment('자료.bin', bytes)]); await page.select([attachment('second.txt', 'Second')]);
  await page.submit();
  assert.equal(page.mutations.length, 1);
  const call = page.mutations[0];
  assert.equal(call.path, '/api/posts/7');
  assert.equal(call.options.method, 'PUT');
  assert.ok(call.options.body instanceof NativeFormData);
  assert.deepEqual([...call.options.body.keys()], ['post', 'files', 'files']);
  assert.equal(call.options.body.get('post').type, 'application/json');
  assert.deepEqual(JSON.parse(await call.options.body.get('post').text()), {
    title, content, updatedAt: initialPost.updatedAt, attachmentIds: [11, 12], deletedFileIds: [12]
  });
  const uploaded = call.options.body.getAll('files');
  assert.deepEqual(Array.from(uploaded, file => file.name), ['자료.bin', 'second.txt']);
  assert.deepEqual(new Uint8Array(await uploaded[0].arrayBuffer()), bytes);
  assert.equal(await uploaded[1].text(), 'Second');
  assert.deepEqual(page.redirects, [{ method: 'replace', path: '/post.html?id=7&keyword=Spring&page=2&size=50' }]);
  assert.equal(page.storageWrites.length, 0);
  assert.equal((await page.windowEvent('beforeunload')).defaultPrevented, false);
});

await check('Maximum valid text lengths save, while live error correction does not steal focus', async () => {
  const page = await harness(); page.fill('t'.repeat(200), 'c'.repeat(10000)); await page.submit();
  assert.equal(page.mutations.length, 1);
  const raw = JSON.parse(await page.mutations[0].options.body.get('post').text());
  assert.equal(raw.title.length, 200); assert.equal(raw.content.length, 10000);
  const correcting = await harness(); correcting.fill('', ''); await correcting.submit();
  correcting.node('post-content').focus(); correcting.node('post-content').value = '작성 중';
  await correcting.input('post-content');
  assert.equal(correcting.document.activeElement, correcting.node('post-content'));
  assert.equal(correcting.node('post-title').getAttribute('maxlength'), undefined);
  assert.equal(correcting.node('post-content').getAttribute('maxlength'), undefined);
});

await check('Duplicate submits and file changes are blocked during session preflight and the PUT request', async () => {
  const preflight = deferred(), mutation = deferred(); let sessions = 0;
  const page = await harness({ session: () => ++sessions === 1 ? authenticated : preflight.promise, mutate: () => mutation.promise });
  page.fill(); await page.select([attachment()]);
  const first = page.submit(); await flush(); await page.submit();
  assert.equal(page.requests.filter(call => call.path === '/api/auth/me').length, 2);
  assert.equal(page.node('post-fields').disabled, true);
  preflight.resolve(authenticated); await flush(); await page.submit();
  await page.select([attachment('ignored.txt')]); await page.remove(0); await page.click(page.existingButton(0));
  await page.windowEvent('focus');
  assert.deepEqual(page.fileNames, ['한글.txt']);
  assert.equal(page.existingButton(0).getAttribute('aria-pressed'), 'false');
  assert.equal(page.mutations.length, 1);
  mutation.resolve({ id: 7 }); await first;
  assert.equal(page.redirects.length, 1);
});

await check('401, 403 and 413 save failures preserve text, deletion selections and new files without automatic retry', async () => {
  for (const status of [401, 403, 413]) {
    let current = authenticated;
    const page = await harness({ session: async () => current, mutate: async () => {
      if (status === 403) current = { id: 2, nickname: '다른 계정' };
      throw new MockApiError('Save failed', status);
    } });
    page.fill(); await page.select([attachment()]); await page.click(page.existingButton(0)); await page.submit();
    assert.equal(page.mutations.length, 1);
    assert.equal(page.node('post-title').value, '제목');
    assert.equal(page.node('post-content').value, '본문');
    assert.deepEqual(page.fileNames, ['한글.txt']);
    assert.equal(page.existingButton(0).getAttribute('aria-pressed'), 'true');
    assert.equal(page.node('submit-error').hidden, false);
    assert.equal(page.node('recovery-panel').hidden, true);
    assert.equal(page.redirects.length, 0);
    if (status === 401 || status === 403) assert.equal(page.node('post-fields').disabled, true);
    if (status === 403) assert.equal(page.requests.filter(call => call.path === '/api/auth/me').length, 3);
    if (status === 413) {
      assert.ok(page.node('submit-error').textContent.includes('새 첨부파일을 줄여'));
      await page.remove(0); assert.equal(page.fileNames.length, 0);
    }
  }
});

await check('Conflicts and unknown results prevent resubmission until confirmed reload replaces the draft with a fresh snapshot', async () => {
  for (const error of [new MockApiError('Conflict', 409), new MockApiError('Response lost', 0, true)]) {
    let currentPost = { ...initialPost }, currentFiles = initialFiles;
    const page = await harness({ loadPost: async () => currentPost, loadFiles: async () => currentFiles, mutate: async () => { throw error; } });
    page.fill(); await page.select([attachment()]); await page.click(page.existingButton(0)); await page.submit();
    assert.equal(page.node('recovery-panel').hidden, false);
    assert.equal(page.node('post-submit').disabled, true);
    assert.equal(page.node('check-post-link').href, '/post.html?id=7');
    assert.equal(page.node('check-post-link').getAttribute('target'), '_blank');
    assert.equal(page.node('check-post-link').getAttribute('rel'), 'noopener');
    await page.submit(); await page.windowEvent('focus');
    assert.equal(page.mutations.length, 1);
    assert.deepEqual(page.fileNames, ['한글.txt']);
    assert.equal(page.existingButton(0).getAttribute('aria-pressed'), 'true');
    const cancelled = page.click('reload-latest'); await page.confirm(false); await cancelled;
    assert.equal(page.node('post-title').value, '제목');
    assert.equal(page.requests.filter(call => call.path === '/api/posts/7' && !call.options.method).length, 1);
    currentPost = { ...initialPost, title: '서버 최신 제목', updatedAt: '2026-09-08T13:00:00' };
    currentFiles = [{ ...initialFiles[0], id: 15, originalFilename: '최신.txt' }];
    const reloaded = page.click('reload-latest'); await page.confirm(true); await reloaded;
    assert.equal(page.node('post-title').value, '서버 최신 제목');
    assert.deepEqual(page.fileNames, []);
    assert.equal(page.existingButton(0).getAttribute('aria-pressed'), 'false');
    assert.equal(page.node('existing-file-list').querySelector('a').href, '/api/files/15/download');
    assert.equal(page.node('recovery-panel').hidden, true);
    assert.equal(page.node('post-submit').disabled, true);
    assert.equal(page.mutations.length, 1);
  }
});

await check('A failed confirmed reload preserves the entire draft and continues to block retry', async () => {
  let failedReload = false;
  const page = await harness({ loadFiles: async () => {
    if (failedReload) throw new MockApiError('Unavailable', 503);
    return initialFiles;
  }, mutate: async () => { throw new MockApiError('Conflict', 409); } });
  page.fill(); await page.select([attachment()]); await page.click(page.existingButton(0)); await page.submit();
  failedReload = true;
  const pending = page.click('reload-latest'); await page.confirm(true); await pending;
  assert.equal(page.node('post-title').value, '제목');
  assert.deepEqual(page.fileNames, ['한글.txt']);
  assert.equal(page.existingButton(0).getAttribute('aria-pressed'), 'true');
  assert.equal(page.node('recovery-panel').hidden, false);
  assert.equal(page.node('post-submit').disabled, true);
  assert.ok(page.node('submit-error').textContent.includes('유지'));
  await page.submit(); assert.equal(page.mutations.length, 1);
});

await check('Session refresh locks other accounts and retains all draft/file state until the author returns', async () => {
  let current = authenticated;
  const page = await harness({ session: async () => current, query: '?id=7&keyword=Spring&page=2&size=50' });
  page.fill(); await page.select([attachment()]); await page.click(page.existingButton(0));
  current = { id: 2, nickname: '다른 계정' }; await page.windowEvent('focus');
  assert.equal(page.node('post-fields').disabled, true);
  await page.submit(); assert.equal(page.mutations.length, 0);
  assert.equal(page.node('post-content').value, '본문');
  assert.deepEqual(page.fileNames, ['한글.txt']);
  assert.equal(page.existingButton(0).getAttribute('aria-pressed'), 'true');
  const calls = page.requests.length; await page.visibility('hidden'); assert.equal(page.requests.length, calls);
  current = authenticated; await page.visibility('visible');
  assert.equal(page.node('post-fields').disabled, false);
  assert.equal(page.node('post-submit').disabled, false);
  for (const kind of ['login', 'signup']) for (const link of page.links(kind)) {
    const destination = new URL(link.href, 'https://board.invalid');
    assert.equal(destination.pathname, `/${kind}.html`);
    assert.equal(destination.searchParams.get('returnTo'), '/edit.html?id=7&keyword=Spring&page=2&size=50');
    assert.equal(link.getAttribute('target'), '_blank');
    assert.equal(link.getAttribute('rel'), 'noopener');
  }
});

await check('Expired preflight sessions and late superseded session responses cannot submit under the wrong user', async () => {
  let current = authenticated;
  const page = await harness({ session: async () => current }); page.fill();
  current = null; await page.submit();
  assert.equal(page.mutations.length, 0);
  assert.equal(page.node('post-fields').disabled, true);
  assert.equal(page.node('post-title').value, '제목');
  const stale = deferred(); let calls = 0;
  const racing = await harness({ session: () => ++calls === 1 ? stale.promise : authenticated });
  await racing.windowEvent('focus'); stale.resolve({ id: 2, nickname: '이전 계정' }); await flush();
  assert.equal(racing.node('session-name').textContent, '작성자님');
  assert.equal(racing.node('post-fields').disabled, false);
});

await check('Cancel confirms unsaved text and file deletion choices while preserving the detail return URL', async () => {
  const page = await harness({ query: '?id=7&keyword=Spring&page=2&size=50' });
  const cancel = page.links('detail').at(-1);
  assert.equal((await page.click(cancel)).defaultPrevented, false);
  await page.click(page.existingButton(0));
  assert.equal((await page.windowEvent('beforeunload')).defaultPrevented, true);
  assert.equal((await page.click(cancel, { ctrlKey: true })).defaultPrevented, false);
  const cancelled = page.click(cancel); await page.confirm(false); await cancelled;
  assert.equal(page.redirects.length, 0);
  assert.equal(page.existingButton(0).getAttribute('aria-pressed'), 'true');
  const confirmed = page.click(cancel); await page.confirm(true); await confirmed;
  assert.deepEqual(page.redirects, [{ method: 'assign', path: '/post.html?id=7&keyword=Spring&page=2&size=50' }]);
  assert.equal(page.mutations.length, 0);
  assert.equal((await page.windowEvent('beforeunload')).defaultPrevented, false);
});

await check('Pagehide cancels a sent PUT and BFCache restoration preserves uncertainty without replaying it', async () => {
  const page = await harness({ mutate: async (path, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(Object.assign(new Error('Aborted'), { name: 'AbortError', mayHaveSucceeded: true })), { once: true });
  }) });
  page.fill(); await page.select([attachment()]); await page.click(page.existingButton(0));
  const pending = page.submit(); await flush();
  assert.equal((await page.click(page.links('detail').at(-1))).defaultPrevented, true);
  assert.equal(page.document.body.querySelectorAll('dialog').length, 0);
  await page.windowEvent('pagehide'); await pending;
  assert.equal(page.mutations[0].options.signal.aborted, true);
  await page.windowEvent('pageshow', { persisted: true });
  assert.equal(page.node('recovery-panel').hidden, false);
  assert.equal(page.node('post-submit').disabled, true);
  assert.equal(page.node('post-title').value, '제목');
  assert.deepEqual(page.fileNames, ['한글.txt']);
  assert.equal(page.existingButton(0).getAttribute('aria-pressed'), 'true');
  await page.submit(); assert.equal(page.mutations.length, 1);
});

await check('Malformed success responses block a second save instead of redirecting to an unrelated post', async () => {
  for (const id of [undefined, 0, 8, 'https://evil.example/']) {
    const page = await harness({ mutate: async () => ({ id }) }); page.fill(); await page.submit();
    assert.equal(page.node('recovery-panel').hidden, false);
    assert.equal(page.redirects.length, 0);
    await page.submit(); assert.equal(page.mutations.length, 1);
  }
});

await check('Browser newline normalization does not create a draft or rewrite untouched text during file-only edits', async () => {
  const rawPost = { ...initialPost, title: '원래\r\n제목', content: '\r첫 줄\r\n둘째 줄\n' };
  const page = await harness({ loadPost: async () => rawPost });
  assert.equal(page.node('post-title').value, '원래제목');
  assert.equal(page.node('post-content').value, '\n첫 줄\n둘째 줄\n');
  assert.equal(page.node('post-submit').disabled, true);
  assert.equal((await page.windowEvent('beforeunload')).defaultPrevented, false);
  await page.submit(); assert.equal(page.mutations.length, 0);
  await page.click(page.existingButton(0)); await page.submit();
  const json = JSON.parse(await page.mutations[0].options.body.get('post').text());
  assert.equal(json.title, rawPost.title);
  assert.equal(json.content, rawPost.content);
  assert.deepEqual(json.deletedFileIds, [11]);
});

await check('BFCache can restart an aborted initial load before the old rejection settles', async () => {
  const abandoned = deferred(); let calls = 0;
  const page = await harness({ loadPost: () => ++calls === 1 ? abandoned.promise : { ...initialPost, title: '다시 불러온 제목' } });
  assert.equal(page.node('edit-form').hidden, true);
  await page.windowEvent('pagehide');
  assert.equal(page.requests.find(call => call.path === '/api/posts/7').options.signal.aborted, true);
  await page.windowEvent('pageshow', { persisted: true });
  assert.equal(calls, 2);
  assert.equal(page.node('edit-form').hidden, false);
  assert.equal(page.node('post-title').value, '다시 불러온 제목');
  abandoned.reject(Object.assign(new Error('Aborted'), { name: 'AbortError' })); await flush();
  assert.equal(page.node('edit-form').hidden, false);
  assert.equal(page.node('post-fields').disabled, false);
  assert.equal(page.node('edit-form').getAttribute('aria-busy'), 'false');
  assert.equal(page.node('post-title').value, '다시 불러온 제목');
  assert.equal(page.mutations.length, 0);
});

export const result = { passed: checks.length, checks };
console.log(`edit: ${checks.length} behavior checks passed`);
