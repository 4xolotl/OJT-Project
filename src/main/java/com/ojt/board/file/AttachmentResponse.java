package com.ojt.board.file;

import io.swagger.v3.oas.annotations.media.Schema;

public record AttachmentResponse(
        Long id,
        String originalFilename,
        long size,
        @Schema(description = "다운로드 MIME 형식. 안전한 다운로드를 위해 항상 application/octet-stream입니다.")
        String contentType,
        String downloadUrl
) {
    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(), attachment.getOriginalFilename(), attachment.getSize(),
                attachment.getContentType(), "/api/files/" + attachment.getId() + "/download");
    }
}
