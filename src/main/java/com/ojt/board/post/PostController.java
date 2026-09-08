package com.ojt.board.post;

import com.ojt.board.global.PageResponse;
import com.ojt.board.auth.BoardPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "게시글 작성", description = "로그인과 CSRF 헤더가 필요합니다. JSON으로 제목과 본문을 작성하거나, multipart/form-data의 post JSON 파트와 선택적인 files 파트로 파일을 함께 첨부합니다.")
    public PostResponse create(@Parameter(hidden = true) @AuthenticationPrincipal BoardPrincipal user,
                               @Valid @RequestBody PostRequest request) {
        return postService.create(user.getId(), request);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "게시글 작성", description = "로그인과 CSRF 헤더가 필요합니다. post 파트는 application/json의 제목·본문이며 files는 선택 사항입니다. 파일은 최대 5개, 개별 10 MiB, 전체 요청 50 MiB까지입니다. 첨부 저장이 실패하면 게시글과 첨부 메타데이터를 롤백하고 저장된 파일 정리를 시도합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    encoding = @Encoding(name = "post", contentType = MediaType.APPLICATION_JSON_VALUE))))
    public PostResponse createWithFiles(@Parameter(hidden = true) @AuthenticationPrincipal BoardPrincipal user,
                                        @Valid @RequestPart("post") PostRequest request,
                                        @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return postService.createWithFiles(user.getId(), request, files);
    }

    @PutMapping(value = "/{postId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "게시글 수정", description = "작성자만 제목과 본문을 수정할 수 있습니다. CSRF 헤더가 필요합니다.")
    public PostResponse update(@PathVariable Long postId, @Parameter(hidden = true) @AuthenticationPrincipal BoardPrincipal user,
                               @Valid @RequestBody PostRequest request) {
        return postService.update(postId, user.getId(), request);
    }

    @PutMapping(value = "/{postId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "게시글 수정", description = "작성자만 수정할 수 있으며 CSRF 헤더가 필요합니다. post JSON에는 제목·본문과 최초 조회한 updatedAt, attachmentIds 및 삭제할 deletedFileIds를 보냅니다. ID 목록은 필수이며 빈 배열을 허용합니다(최대 1000개, 양수·중복 불가). 원본이 달라졌으면 409를 반환합니다. 선택 files는 신규 파일 최대 5개, 개별 10 MiB, 전체 요청 50 MiB까지입니다. 실패하면 DB 변경을 롤백하고 신규 파일 정리를 시도하며, 기존 파일은 커밋 후 삭제합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    encoding = @Encoding(name = "post", contentType = MediaType.APPLICATION_JSON_VALUE))))
    public PostResponse updateWithFiles(@PathVariable Long postId,
                                         @Parameter(hidden = true) @AuthenticationPrincipal BoardPrincipal user,
                                         @Valid @RequestPart("post") PostEditRequest request,
                                         @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return postService.updateWithFiles(postId, user.getId(), request, files);
    }

    @DeleteMapping("/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "게시글 삭제", description = "작성자만 삭제할 수 있습니다. 댓글과 첨부파일도 함께 삭제됩니다. CSRF 헤더가 필요합니다.")
    public void delete(@PathVariable Long postId, @Parameter(hidden = true) @AuthenticationPrincipal BoardPrincipal user) {
        postService.delete(postId, user.getId());
    }
}
