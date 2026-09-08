// Run: node --experimental-vm-modules src/test/js/api.test.mjs
import assert from 'node:assert/strict';
import { Blob } from 'node:buffer';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers';
import { createContext, SourceTextModule } from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/assets/js/api.js', import.meta.url), 'utf8');
// The connected Node runtime exposes Response but may omit the FormData global.
const NativeFormData = globalThis.FormData ?? (await new Response('', {
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
}).formData()).constructor;
const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' }
});
const csrf = () => json({ headerName: 'X-XSRF-TOKEN', token: 'fresh-token' });
const flush = () => new Promise(resolve => setImmediate(resolve));
const aborted = () => Object.assign(new Error('Aborted'), { name: 'AbortError' });
const stalled = signal => new Promise((resolve, reject) => {
  if (signal.aborted) reject(aborted());
  else signal.addEventListener('abort', () => reject(aborted()), { once: true });
});

async function harness(fetchImplementation) {
  const calls = [];
  const delays = [];
  const timers = new Map();
  let timerId = 0;
  const context = createContext({
    URL, Headers, AbortController, FormData: NativeFormData,
    fetch(path, options) { calls.push({ path, options }); return fetchImplementation(path, options); },
    setTimeout(callback, delay) { delays.push(delay); timers.set(++timerId, callback); return timerId; },
    clearTimeout(id) { timers.delete(id); }
  });
  const module = new SourceTextModule(source, { context });
  await module.link(specifier => { assert.fail(`Unexpected API dependency: ${specifier}`); });
  await module.evaluate();
  return {
    ...module.namespace, calls, delays,
    expire() { for (const callback of [...timers.values()]) callback(); },
    get activeTimers() { return timers.size; }
  };
}

async function failure(promise, { status, uncertain, name = 'ApiError' }) {
  const error = await promise.then(() => { throw new Error('Expected a rejection'); }, error => error);
  assert.equal(error.name, name);
  if (status !== undefined) assert.equal(error.status, status);
  assert.equal(error.mayHaveSucceeded, uncertain);
  return error;
}

const checks = [];
async function check(name, run) { await run(); checks.push(name); }

await check('JSON requests preserve encoding, session cookies and a fresh CSRF request per mutation', async () => {
  let token = 0;
  const api = await harness((path, options) => path === '/api/auth/csrf'
    ? json({ headerName: 'X-XSRF-TOKEN', token: 'token-' + ++token })
    : json({ id: 1 }, options.method === 'POST' ? 201 : 200));
  assert.equal((await api.request('/api/posts/1')).id, 1);
  for (const method of ['POST', 'PUT']) await api.request('/api/posts', { method, body: { title: '한글 🧪', content: 'Content' } });
  assert.deepEqual(api.calls.map(call => call.path), ['/api/posts/1', '/api/auth/csrf', '/api/posts', '/api/auth/csrf', '/api/posts']);
  assert.equal(api.calls[2].options.body, JSON.stringify({ title: '한글 🧪', content: 'Content' }));
  assert.equal(api.calls[2].options.headers.get('Content-Type'), 'application/json');
  assert.equal(api.calls[2].options.headers.get('X-XSRF-TOKEN'), 'token-1');
  assert.equal(api.calls[4].options.headers.get('X-XSRF-TOKEN'), 'token-2');
  for (const { options } of api.calls) {
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.redirect, 'error');
    assert.equal(options.headers.get('Accept'), 'application/json');
  }
  assert.ok(api.delays.every(delay => delay === 15000));
  assert.equal(api.activeTimers, 0);
});

await check('Multipart preserves the JSON part, repeated binary files and browser-generated boundary', async () => {
  const form = new NativeFormData();
  form.append('post', new Blob([JSON.stringify({ title: '첨부 글', content: '본문 🧪' })], { type: 'application/json' }), 'post.json');
  const bytes = new Uint8Array([0, 1, 127, 128, 255]);
  form.append('files', new Blob([bytes], { type: 'application/octet-stream' }), '한글.bin');
  form.append('files', new Blob(['second']), 'second.txt');
  const api = await harness(path => path === '/api/auth/csrf' ? csrf() : json({ id: 7 }, 201));
  assert.equal((await api.request('/api/posts', { method: 'POST', body: form })).id, 7);
  assert.equal(api.calls.length, 2);
  const options = api.calls[1].options;
  assert.equal(options.body, form);
  assert.equal(options.headers.get('Content-Type'), null);
  assert.equal(options.headers.get('X-XSRF-TOKEN'), 'fresh-token');
  assert.equal(options.credentials, 'same-origin');
  assert.equal(options.redirect, 'error');
  assert.equal(form.get('post').type, 'application/json');
  assert.equal(JSON.parse(await form.get('post').text()).content, '본문 🧪');
  assert.equal(form.getAll('files').length, 2);
  assert.deepEqual(new Uint8Array(await form.getAll('files')[0].arrayBuffer()), bytes);
  const encoded = new Request('https://board.invalid/api/posts', { method: 'POST', headers: options.headers, body: form });
  assert.match(encoded.headers.get('Content-Type'), /^multipart\/form-data; boundary=/);
  assert.equal(api.delays[0], 60000);
  assert.equal(api.activeTimers, 0);
});

await check('204 and HEAD return null and currentUser maps only 401 to null', async () => {
  const api = await harness((path, options) => path === '/api/auth/csrf' ? csrf()
    : path === '/api/auth/me' ? json({ message: '로그인이 필요합니다.' }, 401)
      : new Response(null, { status: options.method === 'HEAD' ? 200 : 204 }));
  assert.equal(await api.request('/api/posts/1', { method: 'DELETE' }), null);
  assert.equal(await api.request('/api/posts', { method: 'HEAD' }), null);
  assert.equal(await api.currentUser(), null);
  const unavailable = await harness(() => json({ message: '다시 시도해 주세요.' }, 503));
  await failure(unavailable.currentUser(), { status: 503, uncertain: false });
});

await check('HTTP 4xx mutation refusals retain their status and message without uncertainty', async () => {
  for (const status of [400, 401, 403, 409, 413]) {
    const api = await harness(path => path === '/api/auth/csrf' ? csrf() : json({ message: '요청을 확인해 주세요.' }, status));
    const error = await failure(api.request('/api/posts', { method: 'POST', body: {} }), { status, uncertain: false });
    assert.equal(error.message, '요청을 확인해 주세요.');
    assert.equal(api.calls.length, 2);
  }
});

await check('Server errors after a mutation may represent a committed change and are never retried', async () => {
  for (const status of [500, 503]) {
    const api = await harness(path => path === '/api/auth/csrf' ? csrf() : json({ message: '처리 중 오류가 발생했습니다.' }, status));
    await failure(api.request('/api/posts', { method: 'POST', body: {} }), { status, uncertain: true });
    assert.equal(api.calls.length, 2);
  }
});

await check('Unreadable successful mutation JSON is uncertain but unreadable GET JSON is not', async () => {
  const api = await harness(path => path === '/api/auth/csrf' ? csrf() : new Response('not JSON', { status: 201 }));
  await failure(api.request('/api/posts', { method: 'POST', body: {} }), { status: 201, uncertain: true });
  await failure(api.request('/api/posts'), { status: 201, uncertain: false });
});

await check('Failed or malformed CSRF responses do not mark an unsent mutation as uncertain', async () => {
  for (const response of [json({ message: 'Unavailable' }, 503), json({ token: 'missing-header' }), new Response('invalid', { status: 200 })]) {
    const api = await harness(() => response);
    const error = await api.request('/api/posts', { method: 'POST', body: {} }).catch(error => error);
    assert.equal(error.name, 'ApiError');
    assert.equal(error.mayHaveSucceeded, false);
    assert.equal(api.calls.length, 1);
  }
});

await check('Network failures are uncertain only once the mutation fetch has been attempted', async () => {
  for (const afterCsrf of [false, true]) {
    const api = await harness(path => {
      if (afterCsrf && path === '/api/auth/csrf') return csrf();
      throw new TypeError('Network failure');
    });
    await failure(api.request('/api/posts', { method: 'POST', body: {} }), { status: 0, uncertain: afterCsrf });
    assert.equal(api.calls.length, afterCsrf ? 2 : 1);
  }
});

await check('Invalid paths and unserializable bodies fail before any fetch with uncertainty false', async () => {
  const api = await harness(() => { throw new Error('Fetch must not run'); });
  const circular = {}; circular.self = circular;
  for (const [path, options] of [
    ['https://evil.example/api/posts', {}], ['/api/../outside', {}],
    ['/api/posts', { method: 'POST', body: circular }], ['/api/posts', { method: 'GET', body: new NativeFormData() }]
  ]) await failure(api.request(path, options), { status: 0, uncertain: false });
  assert.equal(api.calls.length, 0);
});

await check('JSON and upload timeouts distinguish CSRF preparation from an attempted mutation', async () => {
  for (const multipart of [false, true]) {
    for (const afterCsrf of [false, true]) {
      const api = await harness((path, options) => afterCsrf && path === '/api/auth/csrf' ? csrf() : stalled(options.signal));
      const promise = api.request('/api/posts', { method: 'POST', body: multipart ? new NativeFormData() : {} });
      await flush();
      assert.equal(api.delays[0], multipart ? 60000 : 15000);
      const rejected = failure(promise, { status: 0, uncertain: afterCsrf });
      api.expire();
      await rejected;
      assert.equal(api.calls.length, afterCsrf ? 2 : 1);
      assert.equal(api.activeTimers, 0);
    }
  }
});

await check('Caller cancellation reports whether a mutation was sent and never resubmits it', async () => {
  for (const stage of ['before', 'csrf', 'mutation']) {
    const controller = new AbortController();
    if (stage === 'before') controller.abort();
    const api = await harness((path, options) => stage === 'mutation' && path === '/api/auth/csrf' ? csrf() : stalled(options.signal));
    const promise = api.request('/api/posts', { method: 'POST', body: {}, signal: controller.signal });
    const rejected = failure(promise, { uncertain: stage === 'mutation', name: 'AbortError' });
    await flush();
    controller.abort();
    await rejected;
    assert.equal(api.calls.length, stage === 'before' ? 0 : stage === 'csrf' ? 1 : 2);
    assert.equal(api.activeTimers, 0);
  }
});

await check('Cancellation immediately after CSRF still prevents the actual mutation fetch', async () => {
  const controller = new AbortController();
  const api = await harness(() => { controller.abort(); return csrf(); });
  await failure(api.request('/api/posts', { method: 'POST', body: {}, signal: controller.signal }), {
    uncertain: false, name: 'AbortError'
  });
  assert.equal(api.calls.length, 1);
});

await check('Cancellation while reading a mutation body honors the HTTP status already received', async () => {
  for (const [status, uncertain] of [[400, false], [401, false], [413, false], [201, true], [503, true]]) {
    const controller = new AbortController();
    const api = await harness((path, options) => path === '/api/auth/csrf' ? csrf() : {
      status, ok: status < 400, text: () => stalled(options.signal)
    });
    const promise = api.request('/api/posts', { method: 'POST', body: {}, signal: controller.signal });
    const rejected = failure(promise, { uncertain, name: 'AbortError' });
    await flush(); controller.abort(); await rejected;
    assert.equal(api.calls.length, 2);
    assert.equal(api.activeTimers, 0);
  }
});

await check('A body-read failure preserves an already received mutation status for uncertainty decisions', async () => {
  for (const [status, uncertain] of [[400, false], [201, true], [503, true]]) {
    const api = await harness(path => path === '/api/auth/csrf' ? csrf() : {
      status, ok: status < 400, text: async () => { throw new TypeError('Response stream failed'); }
    });
    await failure(api.request('/api/posts', { method: 'POST', body: {} }), { status, uncertain });
    assert.equal(api.calls.length, 2);
  }
});

export const result = { passed: checks.length, checks };
console.log(`api: ${checks.length} behavior checks passed`);
