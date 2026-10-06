package com.ojt.board.file;

import io.swagger.v3.oas.annotations.media.Schema;

public record AttachmentResponse(
        Long id,
        String originalFilename,
        long size,
        @Schema(description = "업로드 시 확장자와 파일 시그니처를 검증해 저장한 MIME 형식입니다.")
        String contentType,
        String downloadUrl
) {
    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(), attachment.getOriginalFilename(), attachment.getSize(),
                attachment.getContentType(),
                "/api/files/" + attachment.getId() + "/download");
    }
}
