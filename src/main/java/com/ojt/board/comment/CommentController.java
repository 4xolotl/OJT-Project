package com.ojt.board.comment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ojt.board.global.PageResponse;
import com.ojt.board.auth.BoardPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/posts/{postId}/comments")
@RequiredArgsConstructor
@Tag(name = "댓글", description = "게시글별 댓글 조회 및 관리")
public class CommentController {

    private final CommentService commentService;

    @GetMapping
    @Operation(summary = "댓글 목록 조회", description = "로그인 없이 조회할 수 있습니다. 작성 시각, ID 오름차순으로 정렬합니다.")
    @ApiResponse(responseCode = "200", description = "댓글 목록")
    @ApiResponse(responseCode = "400", description = "잘못된 페이지 요청")
    @ApiResponse(responseCode = "404", description = "게시글 없음")
    public PageResponse<CommentResponse> list(
            @PathVariable Long postId,
            @Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지당 댓글 수 (1~100)") @RequestParam(defaultValue = "20") int size
    ) {
        return commentService.list(postId, page, size);
    }

    @PostMapping
    @Operation(summary = "댓글 작성", description = "로그인 세션이 필요합니다.")
    @ApiResponse(responseCode = "201", description = "댓글 작성 완료")
    @ApiResponse(responseCode = "400", description = "내용 검증 실패")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "404", description = "게시글 없음")
    public ResponseEntity<CommentResponse> create(
            @PathVariable Long postId,
            @Parameter(hidden = true) @AuthenticationPrincipal BoardPrincipal user,
            @Valid @RequestBody CommentRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(commentService.create(postId, user.getId(), request));
    }

    @PutMapping("/{commentId}")
    @Operation(summary = "댓글 수정", description = "로그인한 사용자가 댓글을 수정할 수 있습니다.")
    @ApiResponse(responseCode = "200", description = "댓글 수정 완료")
    @ApiResponse(responseCode = "400", description = "내용 검증 실패")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "404", description = "게시글 또는 해당 게시글의 댓글 없음")
    public CommentResponse update(
            @PathVariable Long postId,
            @PathVariable Long commentId,
            @Parameter(hidden = true) @AuthenticationPrincipal BoardPrincipal user,
            @Valid @RequestBody CommentRequest request
    ) {
        return commentService.update(postId, commentId, user.getId(), request);
    }

    @DeleteMapping("/{commentId}")
    @Operation(summary = "댓글 삭제", description = "로그인한 사용자가 댓글을 삭제할 수 있습니다.")
    @ApiResponse(responseCode = "204", description = "댓글 삭제 완료")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "404", description = "게시글 또는 해당 게시글의 댓글 없음")
    public ResponseEntity<Void> delete(
            @PathVariable Long postId,
            @PathVariable Long commentId,
            @Parameter(hidden = true) @AuthenticationPrincipal BoardPrincipal user
    ) {
        commentService.delete(postId, commentId, user.getId());
        return ResponseEntity.noContent().build();
    }
}
