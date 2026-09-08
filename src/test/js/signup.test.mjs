// Run: node --experimental-vm-modules src/test/js/signup.test.mjs
// State/handler tests use a minimal DOM and API doubles; they do not assess browser layout.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers';
import { createContext, SourceTextModule, SyntheticModule } from 'node:vm';

const staticRoot = new URL('../../main/resources/static/', import.meta.url);
const html = await readFile(new URL('signup.html', staticRoot), 'utf8');
const sources = {};
for (const name of ['signup.js', 'navigation.js', 'ui.js', 'password-policy.js']) {
  sources[name] = await readFile(new URL('assets/js/' + name, staticRoot), 'utf8');
}
const flush = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
  let resolve;
  const promise = new Promise(accept => { resolve = accept; });
  return { promise, resolve };
}
class MockApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}

async function harness({ session = async () => null, mutate = async () => ({ id: 1 }), returnTo = '/' } = {}) {
  const ids = new Map();
  const allNodes = [];
  const requests = [];
  const redirects = [];
  const storageWrites = [];
  const windowEvents = new Map();
  let sessionCalls = 0;
  const document = { activeElement: null, querySelectorAll: () => allNodes.filter(node => 'pending' in node.dataset) };
  Object.defineProperty(document, 'cookie', { get: () => '', set: value => storageWrites.push(value) });
  for (const match of html.matchAll(/<([a-z][a-z0-9-]*)\b([^>]*)>/gi)) {
    const attributes = match[2];
    const node = {
      value: '', textContent: '', dataset: {}, listeners: new Map(),
      attributes: new Map([...attributes.matchAll(/\b([a-z][a-z-]*)="([^"]*)"/gi)].map(match => [match[1], match[2]])),
      hidden: /(?:^|\s)hidden(?:\s|=|$)/.test(attributes),
      disabled: /(?:^|\s)disabled(?:\s|=|$)/.test(attributes),
      type: /\btype="([^"]+)"/.exec(attributes)?.[1] ?? '',
      validity: { typeMismatch: false },
      setAttribute(name, value) { this.attributes.set(name, String(value)); },
      getAttribute(name) { return this.attributes.get(name); },
      removeAttribute(name) { this.attributes.delete(name); if (name === 'href') delete this.href; },
      addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(listener);
      },
      focus() { document.activeElement = this; }
    };
    const id = /\bid="([^"]+)"/.exec(attributes)?.[1];
    if (id) { node.id = id; ids.set(id, node); }
    const closingTag = html.indexOf(`</${match[1]}>`, match.index + match[0].length);
    if (closingTag !== -1) node.textContent = html.slice(match.index + match[0].length, closingTag).replace(/<[^>]*>/g, '');
    const pending = /\bdata-pending="([^"]*)"/.exec(attributes);
    if (pending) node.dataset.pending = pending[1];
    allNodes.push(node);
  }
  document.getElementById = id => {
    assert.ok(ids.has(id), `signup.html is missing #${id}`);
    return ids.get(id);
  };
  const storage = new Proxy({
    getItem: () => null,
    setItem: (key, value) => storageWrites.push({ key, value }),
    removeItem() {}
  }, { set(target, key, value) { storageWrites.push({ key, value }); return true; } });
  const window = {
    localStorage: storage, sessionStorage: storage,
    addEventListener(type, listener) {
      if (!windowEvents.has(type)) windowEvents.set(type, []);
      windowEvents.get(type).push(listener);
    }
  };
  Object.defineProperty(window, 'name', { get: () => '', set: value => storageWrites.push(value) });
  const context = createContext({
    document, window, URL, URLSearchParams, AbortController, setTimeout, clearTimeout,
    localStorage: storage, sessionStorage: storage,
    location: { pathname: '/signup.html', hash: '', search: '?returnTo=' + encodeURIComponent(returnTo), replace: path => redirects.push(path) },
    fetch() { throw new Error('Signup tests must not use the network'); }
  });
  const api = new SyntheticModule(['ApiError', 'currentUser', 'request'], function () {
    this.setExport('ApiError', MockApiError);
    this.setExport('currentUser', async () => { sessionCalls++; return session(); });
    this.setExport('request', async (path, options) => { requests.push({ path, options }); return mutate(path, options); });
  }, { context });
  const modules = { 'api.js': api };
  for (const [name, source] of Object.entries(sources)) modules[name] = new SourceTextModule(source, { context, identifier: name });
  await modules['signup.js'].link(specifier => {
    const module = modules[specifier.replace('./', '')];
    assert.ok(module, `Unexpected import: ${specifier}`);
    return module;
  });
  await modules['signup.js'].evaluate();
  await flush();
  const emit = async (id, type, extra = {}) => {
    const event = { defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, ...extra };
    for (const listener of ids.get(id).listeners.get(type) ?? []) await listener(event);
    return event;
  };
  return {
    node: id => ids.get(id), requests, redirects, storageWrites, document,
    get sessionCalls() { return sessionCalls; },
    fill({ email = 'tester@example.com', nickname = 'tester', password = 'test-password', confirmation = password } = {}) {
      ids.get('email').value = email;
      ids.get('nickname').value = nickname;
      ids.get('password').value = password;
      ids.get('password-confirm').value = confirmation;
    },
    submit: () => emit('signup-form', 'submit'),
    click: id => emit(id, 'click'),
    input: id => emit(id, 'input'),
    transfer: (id, type, text) => emit(id, type, {
      [type === 'paste' ? 'clipboardData' : 'dataTransfer']: { getData(format) { assert.equal(format, 'text/plain'); return text; } }
    }),
    async windowEvent(type, extra = {}) {
      for (const listener of windowEvents.get(type) ?? []) await listener(extra);
      await flush();
    }
  };
}

const checks = [];
async function check(name, run) { await run(); checks.push(name); }

await check('Empty, oversized and browser-invalid email never reaches the signup API', async () => {
  for (const email of [' ', 'a'.repeat(90) + '@example.com', 'invalid-email']) {
    const page = await harness();
    page.fill({ email });
    page.node('email').validity.typeMismatch = email === 'invalid-email';
    await page.submit();
    assert.equal(page.requests.length, 0);
    assert.equal(page.node('email').getAttribute('aria-invalid'), 'true');
    assert.equal(page.document.activeElement, page.node('email'));
  }
});

await check('Nickname is trimmed and must remain between 2 and 100 characters', async () => {
  for (const nickname of [' ', ' x ', 'n'.repeat(101)]) {
    const page = await harness();
    page.fill({ nickname });
    await page.submit();
    assert.equal(page.requests.length, 0);
    assert.equal(page.node('nickname').getAttribute('aria-invalid'), 'true');
  }
  for (const nickname of [' ab ', 'n'.repeat(100)]) {
    const page = await harness();
    page.fill({ nickname });
    await page.submit();
    assert.equal(page.requests[0].options.body.nickname, nickname.trim());
  }
});

await check('Signup enforces printable ASCII length limits without requiring mixed categories', async () => {
  for (const password of ['        ', '1234567', 'a'.repeat(73), 'password가', 'password🧪', 'Valid123\n', ' Valid123', 'Valid123 ']) {
    const page = await harness();
    page.fill({ password });
    await page.submit();
    assert.equal(page.requests.length, 0);
    assert.equal(page.node('password').getAttribute('aria-invalid'), 'true');
    assert.equal(page.node('password').value, password);
  }
  for (const password of ['12345678', 'a'.repeat(72), '!!!!!!!!', 'ABCDEFGH']) {
    const page = await harness();
    page.fill({ password });
    await page.submit();
    assert.equal(page.requests.length, 1);
    assert.equal(page.requests[0].options.body.password, password);
  }
});

await check('Both signup password fields guard raw paste/drop and have no truncating maxlength', async () => {
  const page = await harness();
  page.fill({ password: 'Existing1!' });
  for (const id of ['password', 'password-confirm']) {
    assert.equal(page.node(id).getAttribute('maxlength'), undefined);
    for (const type of ['paste', 'drop']) {
      for (const text of ['Valid123\r\n', 'A'.repeat(73)]) {
        const event = await page.transfer(id, type, text);
        assert.equal(event.defaultPrevented, true);
        assert.equal(page.node(id).getAttribute('aria-invalid'), 'true');
        assert.equal(page.node(id + '-error').hidden, false);
        assert.equal(page.node(id).value, 'Existing1!');
        await page.input(id);
      }
      assert.equal((await page.transfer(id, type, 'x')).defaultPrevented, false);
    }
  }
  assert.equal(page.requests.length, 0);
});

await check('Confirmation must exactly match the untrimmed password', async () => {
  for (const confirmation of ['', 'different-password', 'test-password ']) {
    const page = await harness();
    page.fill({ confirmation });
    await page.submit();
    assert.equal(page.requests.length, 0);
    assert.equal(page.node('password-confirm').getAttribute('aria-invalid'), 'true');
  }
});

await check('Only email and nickname are trimmed and confirmation is not sent', async () => {
  const page = await harness();
  const password = String.fromCharCode(65, 97, 48, 33, 34, 39, 92, 96, 126);
  page.fill({ email: '  tester@example.com  ', nickname: '  tester  ', password });
  await page.submit();
  const { path, options } = page.requests[0];
  assert.equal(path, '/api/auth/signup');
  assert.equal(options.method, 'POST');
  assert.equal(options.body.email, 'tester@example.com');
  assert.equal(options.body.nickname, 'tester');
  assert.equal(options.body.password, password);
  assert.deepEqual(Object.keys(options.body).sort(), ['email', 'nickname', 'password']);
});

await check('Each password visibility toggle updates only its own field and accessible state', async () => {
  const page = await harness();
  page.fill();
  for (const id of ['password', 'password-confirm']) {
    await page.click('toggle-' + id);
    assert.equal(page.node(id).type, 'text');
    assert.equal(page.node('toggle-' + id).getAttribute('aria-pressed'), 'true');
    await page.click('toggle-' + id);
    assert.equal(page.node(id).type, 'password');
    assert.equal(page.node('toggle-' + id).getAttribute('aria-pressed'), 'false');
  }
});

await check('Duplicate-email, server, CSRF and network errors preserve input and show appropriate guidance', async () => {
  for (const [status, message] of [
    [400, '이미 가입된 이메일입니다.'], [500, '잠시 후 다시 시도해 주세요.'],
    [403, 'CSRF check failed'], [0, '서버에 연결하지 못했어요.']
  ]) {
    const page = await harness({ mutate: async () => { throw new MockApiError(message, status); } });
    page.fill({ password: 'Retained-Password!1' });
    await page.click('toggle-password');
    await page.click('toggle-password-confirm');
    await page.submit();
    assert.equal(page.node('signup-error').hidden, false);
    assert.ok(page.node('signup-error').textContent.length > 0);
    if (status === 400) assert.equal(page.node('signup-error').textContent, message);
    if (status === 403) assert.ok(page.node('signup-error').textContent.includes('다시'));
    if (status === 0) {
      assert.ok(page.node('signup-error').textContent.includes('완료되었을 수도'));
      assert.ok(page.node('signup-error').textContent.includes('로그인 화면'));
    }
    assert.equal(page.node('email').value, 'tester@example.com');
    assert.equal(page.node('nickname').value, 'tester');
    for (const id of ['password', 'password-confirm']) {
      assert.equal(page.node(id).value, 'Retained-Password!1');
      assert.equal(page.node(id).type, 'password');
    }
    assert.equal(page.node('signup-fields').disabled, false);
    assert.equal(page.redirects.length, 0);
    assert.equal(page.storageWrites.length, 0);
    await page.input('email');
    assert.equal(page.node('signup-error').hidden, true);
  }
});

await check('Duplicate submits and login navigation are blocked during signup', async () => {
  const pending = deferred();
  const page = await harness({ mutate: () => pending.promise });
  page.fill();
  const first = page.submit();
  assert.equal(page.node('signup-fields').disabled, true);
  assert.equal(page.node('signup-form').getAttribute('aria-busy'), 'true');
  assert.equal(page.node('login-link').getAttribute('aria-disabled'), 'true');
  assert.equal(page.node('login-link').href, undefined);
  assert.equal(page.node('login-link').getAttribute('tabindex'), '-1');
  assert.equal((await page.click('login-link')).defaultPrevented, true);
  await page.submit();
  assert.equal(page.requests.length, 1);
  pending.resolve({ id: 1 });
  await first;
  assert.equal(page.redirects.length, 1);
  assert.equal(page.node('login-link').getAttribute('aria-disabled'), 'false');
  assert.equal(page.node('login-link').href, '/login.html?returnTo=%2F');
  assert.equal(page.node('login-link').getAttribute('tabindex'), undefined);
});

await check('Signup redirects to login without auto-login, passwords in URLs, or browser storage', async () => {
  for (const [returnTo, expected] of [
    ['https://evil.example/', '/'],
    ['/post.html?id=9&keyword=Spring&page=2&size=50&preview=1#comments', '/post.html?id=9&keyword=Spring&page=2&size=50#comments']
  ]) {
    const page = await harness({ returnTo });
    const password = 'private-test-password';
    page.fill({ password });
    await page.submit();
    assert.deepEqual(page.requests.map(request => request.path), ['/api/auth/signup']);
    const destination = new URL(page.redirects[0], 'https://board.invalid');
    assert.equal(destination.pathname, '/login.html');
    assert.equal(destination.searchParams.get('returnTo'), expected);
    assert.equal(destination.searchParams.get('registered'), '1');
    assert.deepEqual([...destination.searchParams.keys()].sort(), ['registered', 'returnTo']);
    assert.ok(!page.redirects[0].includes(password));
    for (const id of ['password', 'password-confirm']) assert.equal(page.node(id).value, '');
    assert.equal(page.storageWrites.length, 0);
    assert.equal(page.node('back-link').href, expected);
    assert.equal(page.node('continue-link').href, expected);
    assert.equal(page.node('login-link').href, '/login.html?returnTo=' + encodeURIComponent(expected));
  }
});

await check('A late initial session response cannot overwrite signup success', async () => {
  const initial = deferred();
  const page = await harness({ session: () => initial.promise });
  page.fill();
  await page.submit();
  initial.resolve({ id: 99, nickname: 'Stale session' });
  await flush();
  assert.equal(page.redirects.length, 1);
  assert.equal(page.node('signed-in-panel').hidden, true);
  assert.equal(page.node('signed-in-name').textContent, '');
});

await check('An already authenticated user can log out and switch to signup', async () => {
  const page = await harness({ session: async () => ({ id: 1, nickname: 'Current user' }), mutate: async () => null });
  assert.equal(page.node('signup-panel').hidden, true);
  assert.equal(page.node('signed-in-panel').hidden, false);
  await page.click('switch-account');
  assert.equal(page.requests[0].path, '/api/auth/logout');
  assert.equal(page.requests[0].options.method, 'POST');
  assert.equal(page.node('signup-panel').hidden, false);
  assert.equal(page.node('signed-in-panel').hidden, true);
  assert.equal(page.document.activeElement, page.node('email'));
});

await check('Pagehide clears both passwords, aborts signup, and ignores a late response', async () => {
  const pending = deferred();
  const page = await harness({ mutate: () => pending.promise });
  page.fill();
  const submit = page.submit();
  await page.windowEvent('pagehide');
  assert.equal(page.requests[0].options.signal.aborted, true);
  for (const id of ['password', 'password-confirm']) {
    assert.equal(page.node(id).value, '');
    assert.equal(page.node(id).type, 'password');
  }
  assert.equal(page.node('signup-fields').disabled, false);
  pending.resolve({ id: 1 });
  await submit;
  assert.equal(page.redirects.length, 0);
});

await check('BFCache restoration rechecks the session without replaying signup', async () => {
  let current = null;
  const page = await harness({ session: async () => current });
  page.fill();
  await page.windowEvent('pagehide');
  current = { id: 1, nickname: 'Restored user' };
  await page.windowEvent('pageshow', { persisted: false });
  assert.equal(page.sessionCalls, 1);
  await page.windowEvent('pageshow', { persisted: true });
  assert.equal(page.sessionCalls, 2);
  assert.equal(page.node('signed-in-panel').hidden, false);
  for (const id of ['password', 'password-confirm']) assert.equal(page.node(id).value, '');
  assert.equal(page.requests.length, 0);
});

export const result = { passed: checks.length, checks };
console.log(`signup: ${checks.length} behavior checks passed`);
