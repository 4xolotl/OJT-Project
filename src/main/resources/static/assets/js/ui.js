let toastTimer;

export function notify(message) {
  const toast = document.getElementById('toast');
  clearTimeout(toastTimer);
  toast.textContent = message;
  toast.hidden = false;
  toastTimer = setTimeout(() => { toast.hidden = true; }, 4500);
}

export function announcePending(page) {
  notify(`${page} 화면은 준비 중이에요.`);
}

export function setupPendingActions() {
  document.querySelectorAll('[data-pending]').forEach(button => {
    button.title = `${button.dataset.pending} 화면 준비 중`;
    button.addEventListener('click', () => announcePending(button.dataset.pending));
  });
}

export function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

export function confirmAction({ title, message, confirmLabel = '삭제', danger = true }) {
  return new Promise(resolve => {
    const previousFocus = document.activeElement;
    const dialog = element('dialog', 'confirm-dialog');
    const heading = element('h2', '', title);
    heading.id = 'confirm-title';
    const description = element('p', '', message);
    description.id = 'confirm-description';
    dialog.setAttribute('aria-labelledby', heading.id);
    dialog.setAttribute('aria-describedby', description.id);
    const actions = element('div', 'dialog-actions');
    const cancel = element('button', 'button button-outline', '취소');
    const confirm = element('button', `button ${danger ? 'button-danger' : 'button-primary'}`, confirmLabel);
    cancel.type = confirm.type = 'button';
    cancel.autofocus = true;
    cancel.addEventListener('click', () => dialog.close('cancel'));
    confirm.addEventListener('click', () => dialog.close('confirm'));
    actions.append(cancel, confirm);
    dialog.append(heading, description, actions);
    dialog.addEventListener('close', () => {
      const confirmed = dialog.returnValue === 'confirm';
      dialog.remove();
      if (previousFocus?.isConnected) previousFocus.focus();
      resolve(confirmed);
    }, { once: true });
    document.body.append(dialog);
    dialog.showModal();
  });
}

// Only reconstruct known list parameters; never navigate to a caller-supplied URL.
export function listUrl(query = location.search) {
  const source = new URLSearchParams(query);
  const params = new URLSearchParams();
  const keyword = (source.get('keyword') ?? '').trim().slice(0, 100);
  const page = Number(source.get('page'));
  const size = Number(source.get('size'));
  if (keyword) params.set('keyword', keyword);
  if (Number.isInteger(page) && page > 0 && page <= 2147483647) params.set('page', page);
  if ([10, 50].includes(size)) params.set('size', size);
  return params.size ? `/?${params}` : '/';
}
