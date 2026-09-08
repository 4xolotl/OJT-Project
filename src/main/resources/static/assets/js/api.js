const REQUEST_TIMEOUT_MS = 15000;
const UPLOAD_TIMEOUT_MS = 60000;

export class ApiError extends Error {
  constructor(message, status = 0, mayHaveSucceeded = false) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.mayHaveSucceeded = mayHaveSucceeded;
  }
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
      throw new ApiError('서버 응답을 확인할 수 없어요. 잠시 후 다시 시도해 주세요.', response.status);
    }
  }
  if (!response.ok) {
    const message = typeof data?.message === 'string' && data.message.trim()
      ? data.message
      : '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.';
    throw new ApiError(message, response.status);
  }
  return data;
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
      // Fetch a fresh token for every mutation, including after login or logout.
      const csrf = await fetchJson('/api/auth/csrf', {
        method: 'GET',
        headers: new Headers({ Accept: 'application/json' }),
        signal: controller.signal
      });
      if (typeof csrf?.headerName !== 'string' || !csrf.headerName.trim()
          || typeof csrf.token !== 'string' || !csrf.token.trim()) {
        throw new ApiError('요청 보안 토큰을 확인할 수 없어요. 다시 시도해 주세요.');
      }
      try {
        headers.set(csrf.headerName, csrf.token);
      } catch {
        throw new ApiError('요청 보안 토큰을 확인할 수 없어요. 다시 시도해 주세요.');
      }
    }
    // Mutations are sent once. A timeout can occur after the server has committed a change.
    return await fetchJson(path, {
      method, body: serializedBody, headers, signal: controller.signal,
      onSend: mutates ? () => { mutationSent = true; } : undefined,
      onResponse: mutates ? status => { mutationStatus = status; } : undefined
    });
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
