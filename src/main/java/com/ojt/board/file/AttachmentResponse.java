package com.ojt.board.file;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

public record AttachmentResponse(
        Long id,
        String originalFilename,
        long size,
        @Schema(description = "원본 파일명의 확장자를 기준으로 선택한 응답 MIME 형식입니다.")
        String contentType,
        String downloadUrl
) {
    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(), attachment.getOriginalFilename(), attachment.getSize(),
                MediaTypeFactory.getMediaType(attachment.getOriginalFilename())
                        .orElse(MediaType.APPLICATION_OCTET_STREAM).toString(),
                "/api/files/" + attachment.getId() + "/download");
    }
}
