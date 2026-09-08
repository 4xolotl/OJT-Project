package com.ojt.board.post;

import com.ojt.board.global.PageResponse;
import com.ojt.board.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
@Validated
@Tag(name = "게시글")
public class PostController {

    private final PostService postService;

    @GetMapping
    @Operation(summary = "게시글 목록 및 검색", description = "최신순 목록. page는 0부터, size는 1~100. keyword는 제목·본문의 부분 문자열을 검색합니다.")
    public PageResponse<PostResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String keyword) {
        return postService.list(page, size, keyword);
    }

    @GetMapping("/{postId}")
    @Operation(summary = "게시글 상세 조회")
    public PostResponse get(@PathVariable Long postId) {
        return postService.get(postId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "게시글 작성", description = "로그인과 CSRF 헤더가 필요합니다. 파일은 생성된 게시글의 파일 업로드 API로 첨부합니다.")
    public PostResponse create(@Parameter(hidden = true) @AuthenticationPrincipal User user,
                               @Valid @RequestBody PostRequest request) {
        return postService.create(user.getId(), request);
    }

    @PutMapping("/{postId}")
    @Operation(summary = "게시글 수정", description = "작성자만 제목과 본문을 수정할 수 있습니다. CSRF 헤더가 필요합니다.")
    public PostResponse update(@PathVariable Long postId, @Parameter(hidden = true) @AuthenticationPrincipal User user,
                               @Valid @RequestBody PostRequest request) {
        return postService.update(postId, user.getId(), request);
    }

    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "게시글 삭제", description = "작성자만 삭제할 수 있습니다. 댓글과 첨부파일도 함께 삭제됩니다. CSRF 헤더가 필요합니다.")
    public void delete(@PathVariable Long postId, @Parameter(hidden = true) @AuthenticationPrincipal User user) {
        postService.delete(postId, user.getId());
    }
}
