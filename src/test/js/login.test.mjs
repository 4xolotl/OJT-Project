// Run: node --experimental-vm-modules src/test/js/login.test.mjs
// The DOM fixture checks state and handlers, not browser layout or native form validation.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers';
import { createContext, SourceTextModule, SyntheticModule } from 'node:vm';

const staticRoot = new URL('../../main/resources/static/', import.meta.url);
const html = await readFile(new URL('login.html', staticRoot), 'utf8');
const sources = {};
for (const name of ['login.js', 'navigation.js', 'ui.js', 'password-policy.js']) {
  sources[name] = await readFile(new URL('assets/js/' + name, staticRoot), 'utf8');
}
const flush = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((accept, fail) => { resolve = accept; reject = fail; });
  return { promise, resolve, reject };
}
class MockApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}

async function harness({
  session = async () => null,
  mutate = async () => ({ id: 1 }),
  providers = async () => ({ google: { enabled: false, authorizationUrl: '/oauth2/authorization/google' } }),
  returnTo = '/', registered, oauthError
} = {}) {
  const ids = new Map();
  const allNodes = [];
  const requests = [];
  const providerRequests = [];
  const redirects = [];
  const assignments = [];
  const windowEvents = new Map();
  let sessionCalls = 0;
  const document = { activeElement: null, querySelectorAll: () => allNodes.filter(node => 'pending' in node.dataset) };
  for (const match of html.matchAll(/<([a-z][a-z0-9-]*)\b([^>]*)>/gi)) {
    const attributes = match[2];
    const node = {
      value: '', textContent: '', dataset: {}, listeners: new Map(),
      attributes: new Map([...attributes.matchAll(/\b([a-z][a-z-]*)="([^"]*)"/gi)].map(match => [match[1], match[2]])),
      hidden: /(?:^|\s)hidden(?:\s|=|$)/.test(attributes),
      disabled: /(?:^|\s)disabled(?:\s|=|$)/.test(attributes),
      type: /\btype="([^"]+)"/.exec(attributes)?.[1] ?? '',
      // Tests explicitly supply the browser's typeMismatch result where relevant.
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
    if (closingTag !== -1) {
      node.textContent = html.slice(match.index + match[0].length, closingTag).replace(/<[^>]*>/g, '');
    }
    const pending = /\bdata-pending="([^"]*)"/.exec(attributes);
    if (pending) node.dataset.pending = pending[1];
    allNodes.push(node);
  }
  document.getElementById = id => {
    assert.ok(ids.has(id), `login.html is missing #${id}`);
    return ids.get(id);
  };
  const context = createContext({
    document, URL, URLSearchParams, AbortController, setTimeout, clearTimeout,
    location: {
      pathname: '/login.html', hash: '',
      search: '?returnTo=' + encodeURIComponent(returnTo) + (registered === undefined ? '' : '&registered=' + registered)
        + (oauthError === undefined ? '' : '&oauthError=' + encodeURIComponent(oauthError)),
      replace: path => redirects.push(path),
      assign: path => assignments.push(path)
    },
    window: { addEventListener(type, listener) {
      if (!windowEvents.has(type)) windowEvents.set(type, []);
      windowEvents.get(type).push(listener);
    } },
    fetch() { throw new Error('Login tests must not use the network'); }
  });
  const api = new SyntheticModule(['ApiError', 'currentUser', 'request'], function () {
    this.setExport('ApiError', MockApiError);
    this.setExport('currentUser', async () => { sessionCalls++; return session(); });
    this.setExport('request', async (path, options) => {
      if (path === '/api/auth/providers') {
        providerRequests.push({ path, options });
        return providers(path, options);
      }
      requests.push({ path, options });
      return mutate(path, options);
    });
  }, { context });
  const modules = { 'api.js': api };
  for (const [name, source] of Object.entries(sources)) {
    modules[name] = new SourceTextModule(source, { context, identifier: name });
  }
  await modules['login.js'].link(specifier => {
    const module = modules[specifier.replace('./', '')];
    assert.ok(module, `Unexpected import: ${specifier}`);
    return module;
  });
  await modules['login.js'].evaluate();
  await flush();
  const emit = async (id, type, extra = {}) => {
    const event = { defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, ...extra };
    for (const listener of ids.get(id).listeners.get(type) ?? []) {
      await listener(event);
    }
    return event;
  };
  return {
    node: id => ids.get(id), requests, providerRequests, redirects, assignments, document,
    get sessionCalls() { return sessionCalls; },
    fill(email = 'tester@example.com', password = 'test-password') {
      ids.get('email').value = email;
      ids.get('password').value = password;
    },
    submit: () => emit('login-form', 'submit'),
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

await check('Registration notice appears only after the explicit registration flag', async () => {
  for (const registered of [undefined, '0', '1']) {
    const page = await harness({ registered });
    const notice = page.node('registration-notice');
    assert.equal(notice.hidden, registered !== '1');
    assert.equal(notice.getAttribute('role'), 'status');
    if (registered === '1') {
      assert.ok(notice.textContent.includes('가입'));
      assert.ok(notice.textContent.includes('로그인'));
    }
    assert.equal(page.requests.length, 0);
  }
});

await check('Signup link preserves only the safe original return destination', async () => {
  const page = await harness({ returnTo: '/post.html?id=9&keyword=Spring&page=2&size=10&preview=1#comments', registered: '1' });
  assert.equal(page.node('signup-link').href,
    '/signup.html?returnTo=%2Fpost.html%3Fid%3D9%26keyword%3DSpring%26page%3D2%26size%3D10%23comments');
  const unsafe = await harness({ returnTo: 'https://evil.example/' });
  assert.equal(unsafe.node('signup-link').href, '/signup.html?returnTo=%2F');
});

await check('Empty and browser-invalid email are rejected before login', async () => {
  const page = await harness();
  page.fill('   ');
  await page.submit();
  assert.equal(page.requests.length, 0);
  assert.equal(page.node('email').getAttribute('aria-invalid'), 'true');
  assert.equal(page.document.activeElement, page.node('email'));
  page.fill('invalid-email');
  page.node('email').validity.typeMismatch = true;
  await page.submit();
  assert.equal(page.requests.length, 0);
  assert.equal(page.node('email-error').hidden, false);
});

await check('Empty, overlong, whitespace and non-ASCII login passwords are rejected', async () => {
  for (const password of ['', '  \t ', 'a'.repeat(73), 'password가', 'password🧪', 'Valid123\n', ' Valid123', 'Valid123 ']) {
    const page = await harness();
    page.fill('tester@example.com', password);
    await page.submit();
    assert.equal(page.requests.length, 0);
    assert.equal(page.node('password').getAttribute('aria-invalid'), 'true');
    assert.equal(page.node('password').value, password);
  }
});

await check('Login accepts a single ASCII character, 72 characters and unmixed categories', async () => {
  for (const password of ['x', 'a'.repeat(72), '12345678', '!!!!!!!!']) {
    const page = await harness();
    page.fill('tester@example.com', password);
    await page.submit();
    assert.equal(page.requests.length, 1);
    assert.equal(page.requests[0].options.body.password, password);
    assert.equal(page.redirects[0], '/');
  }
});

await check('The actual login password field guards raw transfers and has no truncating maxlength', async () => {
  const page = await harness();
  page.fill('tester@example.com', 'Existing1!');
  assert.equal(page.node('password').getAttribute('maxlength'), undefined);
  for (const type of ['paste', 'drop']) {
    for (const text of ['Valid123\r\n', 'A'.repeat(73)]) {
      const event = await page.transfer('password', type, text);
      assert.equal(event.defaultPrevented, true);
      assert.equal(page.node('password').getAttribute('aria-invalid'), 'true');
      assert.equal(page.node('password-error').hidden, false);
      assert.equal(page.node('password').value, 'Existing1!');
      await page.input('password');
    }
    assert.equal((await page.transfer('password', type, 'x')).defaultPrevented, false);
  }
  assert.equal(page.requests.length, 0);
});

await check('Only email is trimmed; the password is sent exactly as entered', async () => {
  const page = await harness();
  const password = String.fromCharCode(65, 97, 48, 33, 34, 39, 92, 96, 126);
  page.fill('  tester@example.com  ', password);
  await page.submit();
  assert.equal(page.requests[0].path, '/api/auth/login');
  assert.equal(page.requests[0].options.method, 'POST');
  assert.equal(page.requests[0].options.body.email, 'tester@example.com');
  assert.equal(page.requests[0].options.body.password, password);
  assert.equal(page.node('email').value, 'tester@example.com');
});

await check('Password visibility toggles type and accessible pressed state', async () => {
  const page = await harness();
  page.fill();
  await page.click('toggle-password');
  assert.equal(page.node('password').type, 'text');
  assert.equal(page.node('toggle-password').getAttribute('aria-pressed'), 'true');
  assert.equal(page.node('toggle-password').getAttribute('aria-label'), '비밀번호 숨기기');
  await page.click('toggle-password');
  assert.equal(page.node('password').type, 'password');
  assert.equal(page.node('toggle-password').getAttribute('aria-pressed'), 'false');
});

await check('401 uses a generic error, preserves credentials, and hides the password', async () => {
  const page = await harness({ mutate: async () => { throw new MockApiError('This email does not exist: internal detail', 401); } });
  page.fill('tester@example.com', 'Retained-Password!1');
  await page.click('toggle-password');
  await page.submit();
  assert.equal(page.node('login-error').textContent, '이메일 또는 비밀번호를 확인해 주세요.');
  assert.equal(page.node('login-error').hidden, false);
  assert.equal(page.node('email').value, 'tester@example.com');
  assert.equal(page.node('password').value, 'Retained-Password!1');
  assert.equal(page.node('password').type, 'password');
  assert.equal(page.node('login-fields').disabled, false);
  assert.equal(page.redirects.length, 0);
  await page.input('password');
  assert.equal(page.node('login-error').hidden, true);
});

await check('Duplicate submits are blocked while the first login is pending', async () => {
  const pending = deferred();
  const page = await harness({ mutate: () => pending.promise });
  page.fill();
  const firstSubmit = page.submit();
  assert.equal(page.node('login-fields').disabled, true);
  assert.equal(page.node('login-form').getAttribute('aria-busy'), 'true');
  assert.equal(page.node('signup-link').href, undefined);
  assert.equal(page.node('signup-link').getAttribute('aria-disabled'), 'true');
  await page.submit();
  assert.equal(page.requests.length, 1);
  pending.resolve({ id: 1 });
  await firstSubmit;
  assert.equal(page.redirects.length, 1);
  assert.equal(page.node('signup-link').href, '/signup.html?returnTo=%2F');
});

await check('A late initial session result cannot replace the successful login state', async () => {
  const initialSession = deferred();
  const page = await harness({ session: () => initialSession.promise });
  page.fill();
  await page.submit();
  initialSession.resolve({ id: 77, nickname: 'Stale session' });
  await flush();
  assert.equal(page.redirects.length, 1);
  assert.equal(page.node('signed-in-panel').hidden, true);
  assert.equal(page.node('signed-in-name').textContent, '');
  assert.equal(page.node('password').value, '');
});

await check('Success clears the password and replaces history with an allowlisted return URL', async () => {
  for (const [target, expected] of [
    ['https://evil.example/steal', '/'],
    ['/post.html?id=12&keyword=Spring&page=2&size=50&preview=1#comments', '/post.html?id=12&keyword=Spring&page=2&size=50#comments'],
    ['/edit.html?id=7&keyword=Spring&page=2&size=50&preview=1', '/edit.html?id=7&keyword=Spring&page=2&size=50']
  ]) {
    const page = await harness({ returnTo: target });
    page.fill();
    await page.submit();
    assert.equal(page.redirects[0], expected);
    assert.equal(page.node('password').value, '');
    assert.equal(page.node('back-link').href, expected);
    assert.equal(page.node('continue-link').href, expected);
  }
});

await check('An existing session can log out and return to the login form', async () => {
  const page = await harness({ session: async () => ({ id: 1, nickname: 'Already signed in' }), mutate: async () => null });
  assert.equal(page.node('login-panel').hidden, true);
  assert.equal(page.node('signed-in-panel').hidden, false);
  assert.equal(page.node('signed-in-name').textContent, 'Already signed in');
  await page.click('switch-account');
  assert.equal(page.requests[0].path, '/api/auth/logout');
  assert.equal(page.requests[0].options.method, 'POST');
  assert.equal(page.node('login-panel').hidden, false);
  assert.equal(page.node('signed-in-panel').hidden, true);
  assert.equal(page.document.activeElement, page.node('email'));
});

await check('Pagehide clears secrets, aborts login and ignores a late success', async () => {
  const pending = deferred();
  const page = await harness({ mutate: () => pending.promise });
  page.fill();
  await page.click('toggle-password');
  const submit = page.submit();
  await page.windowEvent('pagehide');
  assert.equal(page.requests[0].options.signal.aborted, true);
  assert.equal(page.node('password').value, '');
  assert.equal(page.node('password').type, 'password');
  assert.equal(page.node('login-fields').disabled, false);
  pending.resolve({ id: 1 });
  await submit;
  assert.equal(page.redirects.length, 0);
});

await check('BFCache restoration rechecks the session without replaying a mutation', async () => {
  let current = null;
  const page = await harness({ session: async () => current });
  page.fill();
  await page.windowEvent('pagehide');
  current = { id: 1, nickname: 'Restored session' };
  await page.windowEvent('pageshow', { persisted: false });
  assert.equal(page.sessionCalls, 1);
  await page.windowEvent('pageshow', { persisted: true });
  assert.equal(page.sessionCalls, 2);
  assert.equal(page.node('signed-in-panel').hidden, false);
  assert.equal(page.node('signed-in-name').textContent, 'Restored session');
  assert.equal(page.node('password').value, '');
  assert.equal(page.requests.length, 0);
});

const enabledGoogle = async () => ({ google: { enabled: true, authorizationUrl: '/oauth2/authorization/google' } });

await check('Disabled Google login leaves the ordinary login form available', async () => {
  const page = await harness();
  assert.equal(page.providerRequests.length, 1);
  assert.equal(page.node('google-login').disabled, true);
  assert.equal(page.node('google-retry').hidden, false);
  assert.ok(page.node('google-status').textContent.includes('준비'));
  assert.equal(page.node('login-fields').disabled, false);
  await page.click('google-login');
  assert.equal(page.assignments.length, 0);
  page.fill();
  await page.submit();
  assert.equal(page.requests[0].path, '/api/auth/login');
});

await check('Provider lookup failure has a fixed message and can be retried', async () => {
  let fail = true;
  const page = await harness({ providers: async () => {
    if (fail) throw new Error('private provider configuration');
    return enabledGoogle();
  } });
  assert.equal(page.node('google-login').disabled, true);
  assert.equal(page.node('google-retry').hidden, false);
  assert.ok(page.node('google-status').textContent.includes('확인하지 못했어요'));
  assert.ok(!page.node('google-status').textContent.includes('private'));
  assert.equal(page.node('login-fields').disabled, false);
  fail = false;
  await page.click('google-retry');
  assert.equal(page.providerRequests.length, 2);
  assert.equal(page.node('google-login').disabled, false);
  assert.equal(page.node('google-status').hidden, true);
});

await check('A pending provider lookup neither blocks passwords nor sends duplicate lookups', async () => {
  const pending = deferred();
  const page = await harness({ providers: () => pending.promise });
  assert.equal(page.node('login-fields').disabled, false);
  await page.click('google-retry');
  assert.equal(page.providerRequests.length, 1);
  page.fill();
  await page.submit();
  assert.equal(page.requests.length, 1);
  pending.resolve(await enabledGoogle());
  await flush();
});

await check('Google is enabled only for the exact boolean and fixed local authorization endpoint', async () => {
  for (const google of [
    {}, { enabled: 'true', authorizationUrl: '/oauth2/authorization/google' },
    { enabled: true, authorizationUrl: 'https://evil.example/' },
    { enabled: true, authorizationUrl: '//evil.example/' },
    { enabled: true, authorizationUrl: '/oauth2/authorization/google?returnTo=https://evil.example/' },
    { enabled: true, authorizationUrl: '/api/auth/login' }
  ]) {
    const page = await harness({ providers: async () => ({ google }) });
    assert.equal(page.node('google-login').disabled, true);
    await page.click('google-login');
    assert.equal(page.assignments.length, 0);
    assert.equal(page.node('google-retry').hidden, false);
  }
});

await check('Google uses a sanitized return URL and clears the password before leaving', async () => {
  for (const [returnTo, expected] of [
    ['/write.html?keyword=Spring&page=2&size=10&preview=1', '/write.html?keyword=Spring&page=2&size=10'],
    ['/edit.html?id=7&keyword=Spring&page=2&size=50&preview=1', '/edit.html?id=7&keyword=Spring&page=2&size=50'],
    ['/post.html?id=7&keyword=Spring#comments', '/post.html?id=7&keyword=Spring'],
    ['https://evil.example/', '/']
  ]) {
    const page = await harness({ providers: enabledGoogle, returnTo });
    page.fill();
    await page.click('toggle-password');
    await page.click('google-login');
    // The shared navigation helper permits #comments for a valid post detail.
    const safeExpected = returnTo.includes('#comments') ? expected + '#comments' : expected;
    assert.equal(page.assignments[0], '/oauth2/authorization/google?returnTo=' + encodeURIComponent(safeExpected));
    assert.equal(page.node('password').value, '');
    assert.equal(page.node('password').type, 'password');
    assert.equal(page.node('login-fields').disabled, true);
    assert.equal(page.node('signup-link').href, undefined);
    assert.equal(page.requests.length, 0);
  }
});

await check('Google navigation blocks duplicate Google clicks and password form submits', async () => {
  const page = await harness({ providers: enabledGoogle });
  page.fill();
  await page.click('google-login');
  await page.click('google-login');
  await page.submit();
  await page.click('google-retry');
  assert.equal(page.assignments.length, 1);
  assert.equal(page.requests.length, 0);
  assert.equal(page.providerRequests.length, 1);
});

await check('A pending password login blocks Google navigation even after provider lookup completes', async () => {
  const mutation = deferred();
  const provider = deferred();
  const page = await harness({ mutate: () => mutation.promise, providers: () => provider.promise });
  page.fill();
  const submission = page.submit();
  provider.resolve(await enabledGoogle());
  await flush();
  assert.equal(page.node('google-login').disabled, true);
  await page.click('google-login');
  assert.equal(page.assignments.length, 0);
  mutation.reject(new MockApiError('failed', 401));
  await submission;
  assert.equal(page.node('google-login').disabled, false);
});

await check('An existing session cannot begin Google login until logout completes', async () => {
  const logout = deferred();
  const page = await harness({ providers: enabledGoogle, session: async () => ({ id: 1, nickname: 'Existing' }), mutate: () => logout.promise });
  await page.click('google-login');
  assert.equal(page.assignments.length, 0);
  const switching = page.click('switch-account');
  await page.click('google-login');
  assert.equal(page.assignments.length, 0);
  logout.resolve(null);
  await switching;
  assert.equal(page.node('google-login').disabled, false);
  await page.click('google-login');
  assert.equal(page.assignments.length, 1);
});

await check('A late session response does not interfere with Google navigation', async () => {
  const session = deferred();
  const page = await harness({ providers: enabledGoogle, session: () => session.promise });
  await page.click('google-login');
  session.resolve({ id: 5, nickname: 'Stale' });
  await flush();
  assert.equal(page.node('signed-in-panel').hidden, true);
  assert.equal(page.assignments.length, 1);
});

await check('OAuth callback errors use fixed messages and never reflect arbitrary parameters', async () => {
  const fragments = { cancelled: '취소', email_conflict: '기존 이메일과 비밀번호', invalid_identity: '계정 정보를 확인', failed: '실패' };
  for (const [oauthError, expected] of Object.entries(fragments)) {
    const page = await harness({ oauthError });
    assert.equal(page.node('login-error').hidden, false);
    assert.ok(page.node('login-error').textContent.includes(expected));
    assert.equal(page.requests.length, 0);
    assert.equal(page.assignments.length, 0);
  }
  for (const oauthError of ['<img src=x onerror=alert(1)>', '__proto__', 'constructor']) {
    const page = await harness({ oauthError });
    assert.equal(page.node('login-error').textContent, 'Google 로그인에 실패했어요. 다시 시도하거나 이메일로 로그인해 주세요.');
  }
});

await check('Pagehide aborts provider lookup and ignores its late response', async () => {
  const provider = deferred();
  const page = await harness({ providers: () => provider.promise });
  await page.windowEvent('pagehide');
  assert.equal(page.providerRequests[0].options.signal.aborted, true);
  provider.resolve(await enabledGoogle());
  await flush();
  assert.equal(page.node('google-login').disabled, true);
});

await check('Returning from Google through BFCache unlocks the form and refreshes both session and providers', async () => {
  let current = null;
  const page = await harness({ providers: enabledGoogle, session: async () => current });
  page.fill();
  await page.click('google-login');
  await page.windowEvent('pagehide');
  current = { id: 1, nickname: 'Google user' };
  await page.windowEvent('pageshow', { persisted: true });
  assert.equal(page.providerRequests.length, 2);
  assert.equal(page.sessionCalls, 2);
  assert.equal(page.node('signed-in-panel').hidden, false);
  assert.equal(page.node('google-login').disabled, true);
  assert.equal(page.node('password').value, '');
  assert.equal(page.requests.length, 0);
  assert.equal(page.assignments.length, 1);
});

export const result = { passed: checks.length, checks };
console.log(`login: ${checks.length} behavior checks passed`);
