const REQUEST_TIMEOUT_MS = 15000;
const UPLOAD_TIMEOUT_MS = 60000;
const AUTH_STATE_CHANGE_PATHS = new Set(['/api/auth/login', '/api/auth/logout']);

let cachedCsrfToken = null;
let csrfTokenRequest = null;
let csrfTokenGeneration = 0;

export class ApiError extends Error {
  constructor(message, status = 0, mayHaveSucceeded = false, requestPath, retryAfterSeconds) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.mayHaveSucceeded = mayHaveSucceeded;
    this.requestPath = requestPath;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

function retryAfterSeconds(response) {
  const value = response.headers.get('Retry-After');
  if (value === null || !/^[0-9]+$/.test(value)) return undefined;
  const seconds = Number(value);
  return Number.isSafeInteger(seconds) && seconds > 0 ? seconds : undefined;
}

function validatePath(path) {
  if (typeof path !== 'string' || !path.startsWith('/api/') || /[\\\s#]/.test(path)) {
    throw new ApiError('올바른 API 경로가 아니에요.');
  }
  const base = 'https://board.invalid';
  const url = new URL(path, base);
  if (url.origin !== base || !url.pathname.startsWith('/api/')) {
    throw new ApiError('올바른 API 경로가 아니에요.');
  }
}

function cancelledError(mayHaveSucceeded = false) {
  const error = new Error('요청이 취소되었어요.');
  error.name = 'AbortError';
  error.mayHaveSucceeded = mayHaveSucceeded;
  return error;
}

async function fetchJson(path, { method, body, headers, signal, onSend, onResponse }) {
  if (signal.aborted) throw cancelledError();
  onSend?.();
  const response = await fetch(path, {
    method,
    body,
    headers,
    signal,
    credentials: 'same-origin',
    redirect: 'error'
  });
  onResponse?.(response.status);
  if (response.ok && (response.status === 204 || method === 'HEAD')) return null;

  const text = await response.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    if (response.ok) {
      throw new ApiError(
        '서버 응답을 확인할 수 없어요. 잠시 후 다시 시도해 주세요.',
        response.status,
        false,
        path
      );
    }
  }
  if (!response.ok) {
    const message = typeof data?.message === 'string' && data.message.trim()
      ? data.message
      : '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.';
    throw new ApiError(message, response.status, false, path, retryAfterSeconds(response));
  }
  return data;
}

function invalidateCsrfToken() {
  cachedCsrfToken = null;
  csrfTokenGeneration += 1;
}

function startCsrfTokenRequest() {
  const controller = new AbortController();
  const generation = csrfTokenGeneration;
  const request = { controller, consumers: 0, settled: false, promise: null };
  request.promise = (async () => {
    try {
      const csrf = await fetchJson('/api/auth/csrf', {
        method: 'GET',
        headers: new Headers({ Accept: 'application/json' }),
        signal: controller.signal
      });
      if (typeof csrf?.headerName !== 'string' || !csrf.headerName.trim()
          || typeof csrf.token !== 'string' || !csrf.token.trim()) {
        throw new ApiError('요청 보안 토큰을 확인할 수 없어요. 다시 시도해 주세요.');
      }
      const token = { headerName: csrf.headerName, token: csrf.token };
      if (generation === csrfTokenGeneration) cachedCsrfToken = token;
      return token;
    } finally {
      request.settled = true;
      if (csrfTokenRequest === request) csrfTokenRequest = null;
    }
  })();
  csrfTokenRequest = request;
  return request;
}

async function getCsrfToken(signal) {
  if (signal.aborted) throw cancelledError();
  if (cachedCsrfToken) return cachedCsrfToken;

  const request = csrfTokenRequest ?? startCsrfTokenRequest();
  request.consumers += 1;
  let cancel;
  const cancellation = new Promise((resolve, reject) => {
    cancel = () => reject(cancelledError());
    signal.addEventListener('abort', cancel, { once: true });
  });
  try {
    return await Promise.race([request.promise, cancellation]);
  } finally {
    signal.removeEventListener('abort', cancel);
    request.consumers -= 1;
    // A cancelled caller must not abort a token request still needed by another mutation.
    if (request.consumers === 0 && !request.settled) request.controller.abort();
  }
}

export async function request(path, { method = 'GET', body, signal } = {}) {
  validatePath(path);
  if (typeof method !== 'string') throw new ApiError('요청 방식을 확인해 주세요.');
  method = method.toUpperCase();
  const mutates = method !== 'GET' && method !== 'HEAD';
  const multipart = typeof FormData !== 'undefined' && body instanceof FormData;
  let serializedBody;
  if (body !== undefined) {
    if (!mutates || body === null || typeof body !== 'object') {
      throw new ApiError('요청 데이터를 확인해 주세요.');
    }
    if (multipart) {
      serializedBody = body;
    } else {
      try {
        serializedBody = JSON.stringify(body);
      } catch {
        throw new ApiError('요청 데이터를 JSON으로 변환하지 못했어요.');
      }
    }
  }

  const controller = new AbortController();
  const cancel = () => controller.abort();
  signal?.addEventListener('abort', cancel, { once: true });
  if (signal?.aborted) controller.abort();
  let timedOut = false;
  let mutationSent = false;
  let mutationStatus = 0;
  const mayHaveSucceeded = status => mutationSent
    && (status === 0 || status >= 500 || (status >= 200 && status < 300));
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, multipart ? UPLOAD_TIMEOUT_MS : REQUEST_TIMEOUT_MS);

  try {
    const headers = new Headers({ Accept: 'application/json' });
    if (serializedBody !== undefined && !multipart) headers.set('Content-Type', 'application/json');
    if (mutates) {
      const csrf = await getCsrfToken(controller.signal);
      try {
        headers.set(csrf.headerName, csrf.token);
      } catch {
        throw new ApiError('요청 보안 토큰을 확인할 수 없어요. 다시 시도해 주세요.');
      }
    }
    // Mutations are sent once. A timeout can occur after the server has committed a change.
    const result = await fetchJson(path, {
      method, body: serializedBody, headers, signal: controller.signal,
      onSend: mutates ? () => { mutationSent = true; } : undefined,
      onResponse: mutates ? status => {
        mutationStatus = status;
        // The API deliberately uses one generic 403 response for authorization and CSRF failures.
        // Spring also clears the server-side expectation after successful authentication changes.
        // Clearing only the cache is safe; this client never retries a rejected mutation.
        if (status === 403 || (status >= 200 && status < 300 && AUTH_STATE_CHANGE_PATHS.has(path))) {
          invalidateCsrfToken();
        }
      } : undefined
    });
    return result;
  } catch (error) {
    if (error instanceof ApiError) {
      error.mayHaveSucceeded = mayHaveSucceeded(error.status);
      throw error;
    }
    if (timedOut) {
      throw new ApiError('응답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.', mutationStatus,
        mayHaveSucceeded(mutationStatus));
    }
    if (signal?.aborted) throw cancelledError(mayHaveSucceeded(mutationStatus));
    throw new ApiError('서버에 연결하지 못했어요. 연결 상태를 확인해 주세요.', mutationStatus,
      mayHaveSucceeded(mutationStatus));
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener('abort', cancel);
  }
}

export async function currentUser() {
  try {
    return await request('/api/auth/me');
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw error;
  }
}
