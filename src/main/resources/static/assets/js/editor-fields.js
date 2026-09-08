// Shared field rules for creating and editing posts. The server validates every request again.
export function postTextErrors(title, content) {
  return {
    title: !title.trim() ? '제목을 입력해 주세요.' : title.length > 200 ? '제목은 200자까지 입력할 수 있어요.' : '',
    content: !content.trim() ? '내용을 입력해 주세요.' : content.length > 10000 ? '내용은 10,000자까지 입력할 수 있어요.' : ''
  };
}

export function validateFiles(candidate) {
  if (candidate.length > 5) return '새 파일은 한 번에 최대 5개까지 첨부할 수 있어요.';
  for (const file of candidate) {
    if (!file.size) return '비어 있는 파일은 첨부할 수 없어요.';
    if (file.size > 10 * 1024 * 1024) return '파일 하나의 크기는 10 MiB 이하여야 해요.';
    if (!file.name.trim() || /[\u0000-\u001f\u007f-\u009f/\\]/.test(file.name)
        || ['.', '..'].includes(file.name) || file.name.normalize('NFC').length > 255) {
      return '파일 이름을 확인해 주세요. 제어 문자와 경로 구분자는 사용할 수 없고, 255자까지 허용돼요.';
    }
  }
  if (candidate.reduce((total, file) => total + file.size, 0) >= 50 * 1024 * 1024) {
    return '전체 요청은 제목·내용을 포함해 50 MiB 이하여야 해요. 첨부파일 크기를 줄여 주세요.';
  }
  return '';
}

export function formatFileSize(size) {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`;
  return `${(size / 1024 / 1024).toFixed(1)} MiB`;
}
