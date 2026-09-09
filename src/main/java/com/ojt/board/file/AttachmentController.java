package com.ojt.board.file;

import com.ojt.board.auth.BoardPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@Tag(name = "첨부파일")
public class AttachmentController {

    private final AttachmentService attachmentService;

    @GetMapping("/api/posts/{postId}/files")
    @Operation(summary = "게시글 첨부파일 목록", description = "로그인하지 않아도 조회할 수 있습니다.")
    public List<AttachmentResponse> list(@PathVariable Long postId) {
        return attachmentService.list(postId);
    }

    @PostMapping(value = "/api/posts/{postId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "첨부파일 업로드", description = "로그인한 사용자가 업로드할 수 있습니다. "
            + "multipart의 files 항목을 반복해 1~5개 파일을 전송합니다. 파일당 최대 10MiB, 요청 전체 최대 50MiB입니다.")
    @ApiResponse(responseCode = "201", description = "첨부파일 업로드 완료",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = AttachmentResponse.class))))
    public ResponseEntity<List<AttachmentResponse>> upload(
            @PathVariable Long postId,
            @Parameter(hidden = true) @AuthenticationPrincipal BoardPrincipal user,
            @RequestPart("files") List<MultipartFile> files
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attachmentService.upload(postId, user.getId(), files));
    }

    @GetMapping("/api/files/{fileId}/download")
    @Operation(summary = "첨부파일 다운로드", description = "로그인하지 않아도 파일을 조회할 수 있습니다. 브라우저가 지원하는 파일 형식은 바로 표시합니다.")
    @ApiResponse(responseCode = "200", description = "파일 바이트 스트림",
            content = @Content(mediaType = "*/*",
                    schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<Resource> download(@PathVariable Long fileId) {
        AttachmentService.Download download = attachmentService.download(fileId);
        return ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(download.originalFilename())
                        .orElse(MediaType.APPLICATION_OCTET_STREAM))
                .contentLength(download.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(download.originalFilename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(download.resource());
    }

    @DeleteMapping("/api/files/{fileId}")
    @Operation(summary = "첨부파일 삭제", description = "로그인한 사용자가 삭제할 수 있습니다.")
    @ApiResponse(responseCode = "204", description = "첨부파일 삭제 완료", content = @Content)
    public ResponseEntity<Void> delete(
            @PathVariable Long fileId,
            @Parameter(hidden = true) @AuthenticationPrincipal BoardPrincipal user
    ) {
        attachmentService.delete(fileId, user.getId());
        return ResponseEntity.noContent().build();
    }
}
