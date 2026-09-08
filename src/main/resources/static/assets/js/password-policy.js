// Printable ASCII excluding space: English letters, digits and all 32 ASCII symbols.
// Reject invalid characters rather than removing or normalizing password input.
export function passwordError(value, minimumLength = 1) {
  if (typeof value !== 'string' || !value) return '비밀번호를 입력해 주세요.';
  // A negated character check also rejects a trailing newline; JS $ can match before it.
  if (/[^\x21-\x7e]/.test(value)) {
    return '비밀번호는 영문 대소문자, 숫자, 허용된 특수문자만 사용할 수 있어요. 공백은 사용할 수 없어요.';
  }
  if (value.length < minimumLength || value.length > 72) {
    return minimumLength === 8 ? '비밀번호를 8~72자로 입력해 주세요.' : '비밀번호는 72자까지 입력할 수 있어요.';
  }
  return '';
}

export function guardPasswordTransfer(input, reportError) {
  for (const type of ['paste', 'drop']) {
    input.addEventListener(type, event => {
      // Inspect the transfer before a single-line input can strip CR/LF from it.
      const data = type === 'paste' ? event.clipboardData : event.dataTransfer;
      const text = data?.getData('text/plain');
      if (!text) return;
      const message = passwordError(text);
      if (message) {
        event.preventDefault();
        reportError(message);
      }
    });
  }
}
