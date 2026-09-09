# 게시판 API 명세

기본 주소: `http://localhost:8080`

[실행 방법](../README.md) · [Swagger UI](http://localhost:8080/swagger-ui.html) · [OpenAPI JSON](http://localhost:8080/v3/api-docs)

## 공통 규칙

- JSON 요청·응답은 UTF-8이며, 파일 업로드는 `multipart/form-data`를 사용합니다.
- 로그인은 `JSESSIONID` 세션 쿠키로 유지합니다. 세션 유휴 만료 시간은 30분입니다.
- 웹 화면과 Swagger UI는 `X-XSRF-TOKEN` 헤더를 전송하며, 게시판 API에서 이 헤더는 선택 사항입니다.
- 생성은 `201`, 조회·수정은 `200`, 삭제·로그아웃은 본문 없는 `204`를 반환합니다.
- 작성자 정보는 생성 시 로그인 계정으로 결정하며, 수정으로 바뀌지 않습니다.

## 인증

| 메서드 | 경로 | 기능 | 정상 상태 |
| --- | --- | --- | --- |
| GET | `/api/auth/csrf` | 초기 세션·CSRF 토큰 발급 | 200 |
| GET | `/api/auth/providers` | Google 로그인 사용 가능 여부 | 200 |
| POST | `/api/auth/signup` | 회원가입 | 201 |
| POST | `/api/auth/login` | 일반 로그인 | 200 |
| GET | `/api/auth/me` | 로그인한 사용자 정보 | 200 |
| POST | `/api/auth/logout` | 현재 세션 종료 | 204 |

회원가입은 자동 로그인하지 않습니다. 일반 로그인은 기존 세션이 있으면 해당 세션을 사용합니다.

회원가입 요청:

```json
{"email":"test@example.com","nickname":"tester","password":"password123"}
```

로그인 요청:

```json
{"email":"test@example.com","password":"password123"}
```

회원가입·로그인·내 정보 응답:

```json
{"id":1,"email":"test@example.com","nickname":"tester"}
```

| 입력 | 규칙 |
| --- | --- |
| 이메일 | 이메일 형식, 최대 100자, 중복 불가 |
| 닉네임 | 2–100자, 공백만 입력 불가 |
| 가입 비밀번호 | 4–72자 |
| 로그인 비밀번호 | 1–72자 |

비밀번호는 공백을 제외한 ASCII 문자(`U+0021–U+007E`)만 허용합니다. 영문 대소문자·숫자·특수문자를 사용할 수 있으며 필수 혼합 조건은 없습니다.

Google 로그인은 브라우저에서 `/oauth2/authorization/google`로 시작하며, 콜백 주소는 `/login/oauth2/code/google`입니다. 최초 로그인 시 회원을 생성하고 이후에도 세션 쿠키로 게시판을 이용합니다. 같은 이메일의 일반 계정과 자동 연결하지 않으며, Google 계정은 일반 비밀번호 로그인에 사용할 수 없습니다.

## 게시글

| 메서드 | 경로 | 기능 | 인증 |
| --- | --- | --- | --- |
| GET | `/api/posts` | 목록·제목/본문 검색 | 공개 |
| GET | `/api/posts/{postId}` | 상세 조회 | 공개 |
| POST | `/api/posts` | 작성 | 로그인 |
| PUT | `/api/posts/{postId}` | 수정 | 로그인 |
| DELETE | `/api/posts/{postId}` | 게시글·댓글·첨부파일 삭제 | 로그인 |

JSON 작성·수정 요청:

```json
{"title":"첫 번째 게시글","content":"게시글 본문입니다."}
```

제목은 1–200자, 본문은 1–10,000자이며 공백만 입력할 수 없습니다. 작성·상세·수정 응답은 다음과 같습니다.

```json
{
  "id":1,
  "title":"첫 번째 게시글",
  "content":"게시글 본문입니다.",
  "author":{"id":1,"nickname":"tester"},
  "createdAt":"2026-09-09T08:00:00Z",
  "updatedAt":"2026-09-09T08:00:00Z"
}
```

### 파일과 함께 작성

같은 `POST /api/posts`에 다음 multipart 파트를 전송합니다.

| 파트 | 형식 | 내용 |
| --- | --- | --- |
| `post` | `application/json`, 필수 | `title`, `content` |
| `files` | 파일, 선택 | 신규 파일 최대 5개; 같은 파트 이름 반복 |

### 글과 첨부파일 동시 수정

`PUT /api/posts/{postId}`의 multipart 요청은 다음 JSON을 필수 `post` 파트로 전송합니다. 새 파일이 있으면 `files` 파트를 추가합니다.

```json
{
  "title":"수정한 제목",
  "content":"수정한 내용",
  "updatedAt":"2026-09-09T08:00:00Z",
  "attachmentIds":[11,12],
  "deletedFileIds":[12]
}
```

- `updatedAt`: 처음 조회한 게시글의 수정 시각을 그대로 전달합니다.
- `attachmentIds`: 처음 조회한 전체 첨부파일 ID 목록입니다.
- `deletedFileIds`: 삭제할 ID 목록이며 `attachmentIds`의 부분집합입니다.
- 두 배열은 필수이며 빈 배열을 허용합니다. 각각 최대 1,000개의 중복 없는 양수 ID를 사용합니다.
- 수정 시각 또는 첨부파일 목록이 달라졌으면 `409`를 반환합니다. 최신 내용을 확인한 뒤 다시 요청합니다.

파일 변경 없는 JSON 수정에는 수정 시각·첨부파일 목록을 전달하지 않습니다. 작성·수정 응답의 첨부파일 정보는 파일 목록 API에서 별도로 조회합니다.

## 댓글

| 메서드 | 경로 | 기능 | 인증 |
| --- | --- | --- | --- |
| GET | `/api/posts/{postId}/comments` | 목록 | 공개 |
| POST | `/api/posts/{postId}/comments` | 작성 | 로그인 |
| PUT | `/api/posts/{postId}/comments/{commentId}` | 수정 | 로그인 |
| DELETE | `/api/posts/{postId}/comments/{commentId}` | 삭제 | 로그인 |

작성·수정 요청은 `{"content":"좋은 글 감사합니다."}`입니다. 내용은 1–2,000자이며 공백만 입력할 수 없습니다.

```json
{
  "id":2,
  "postId":1,
  "content":"좋은 글 감사합니다.",
  "author":{"id":2,"nickname":"reader"},
  "createdAt":"2026-09-09T08:01:00Z",
  "updatedAt":"2026-09-09T08:01:00Z"
}
```

댓글이 경로의 게시글에 속하지 않거나 게시글이 없으면 `404`를 반환합니다. 웹 화면의 수정·삭제 버튼은 해당 글·댓글의 작성자에게 표시합니다.

## 목록과 검색

| 파라미터 | 기본값 | 규칙 |
| --- | --- | --- |
| `page` | 0 | 0부터 시작 |
| `size` | 20 | 1–100 |
| `keyword` | 없음 | 게시글 목록 전용, 최대 100자 |

게시글은 최신순, 댓글은 등록순입니다. 게시글은 제목·본문을 대소문자 구분 없이 검색합니다.

```text
GET /api/posts?page=0&size=20&keyword=Spring
GET /api/posts/1/comments?page=0&size=20
```

목록의 `content`에 게시글 또는 댓글 객체가 들어갑니다.

```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0,"first":true,"last":true}
```

## 첨부파일

| 메서드 | 경로 | 기능 | 인증 |
| --- | --- | --- | --- |
| GET | `/api/posts/{postId}/files` | 파일 목록 | 공개 |
| POST | `/api/posts/{postId}/files` | 업로드 | 로그인 |
| GET | `/api/files/{fileId}/download` | 파일 조회·다운로드 | 공개 |
| DELETE | `/api/files/{fileId}` | 파일 삭제 | 로그인 |

업로드는 `multipart/form-data`의 `files` 파트를 사용합니다. 한 요청에 1–5개, 파일당 최대 10 MiB, 전체 요청은 multipart 부가 데이터까지 포함해 최대 50 MiB입니다. 글 작성·수정의 신규 파일에도 같은 제한을 적용합니다.

빈 파일, 잘못된 파일명과 255자를 넘는 파일명은 거부합니다. 업로드·목록 응답은 다음 배열 형식입니다.

```json
[
  {
    "id":3,
    "originalFilename":"설명서.txt",
    "size":120,
    "contentType":"text/plain",
    "downloadUrl":"/api/files/3/download"
  }
]
```

`size`는 바이트 단위입니다. 다운로드 응답은 원본 확장자에 따른 MIME 형식과 `Content-Disposition: inline`을 사용하며, 알 수 없는 형식은 `application/octet-stream`으로 제공합니다.

## 상태 확인

Actuator는 `ADMIN` 권한으로 로그인한 세션에서만 조회할 수 있습니다. 비로그인 요청은 `401`, 일반 회원 요청은 `403`을 반환합니다.

`GET /actuator/health`는 정상일 때 `200`과 `status: UP`, DB·디스크 등의 상태 정보를 반환합니다. `DOWN`·`OUT_OF_SERVICE` 상태는 `503`을 반환합니다. `info`, `metrics`, `mappings`와 health 구성 요소·개별 지표도 GET·HEAD 조회를 지원합니다. 관리 동작을 변경하는 요청은 허용하지 않습니다.

## 오류 응답

```json
{"message":"게시글을 찾을 수 없습니다."}
```

| 상태 | 의미 |
| --- | --- |
| 400 | 입력·JSON·페이지·파일명·파일 개수 오류, 중복 이메일 |
| 401 | 로그인 실패 또는 로그인 필요 |
| 403 | 접근 규칙에 따른 요청 거부 |
| 404 | 대상 없음 또는 댓글의 게시글 경로 불일치 |
| 409 | 동시 수정 충돌 |
| 413 | 파일·요청 용량 초과 |
| 500 | 서버 내부 처리 오류 |
