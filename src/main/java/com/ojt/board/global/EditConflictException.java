package com.ojt.board.global;

public class EditConflictException extends RuntimeException {
    public EditConflictException() {
        super("게시글 또는 첨부파일이 변경되었습니다. 새로고침 후 다시 수정해 주세요.");
    }
}
