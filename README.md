# Spring Boot 게시판

세션 기반 사용자 인증, 게시글·댓글 관리, 첨부파일 업로드·다운로드를 제공하는 게시판 과제 프로젝트입니다. 웹 화면은 디자인 피드백을 반영하며 한 페이지씩 구현합니다. 현재 **게시글 목록·상세 화면**을 사용할 수 있으며, 전체 API는 Swagger UI에서 실행할 수 있습니다.

## 웹 화면

접속 주소: **http://localhost:8080/**

| 화면 | 포함 기능 | 구현 상태 |
| --- | --- | --- |
| 게시글 목록 | 제목·본문 검색, 최신순 목록, 페이지 이동, 페이지 크기 변경 | 구현 |
| 게시글 상세 | 본문, 첨부파일 다운로드, 댓글 작성·수정·삭제, 작성자의 글 삭제 | 구현; 글 수정 버튼은 준비 중 안내 |
| 게시글 작성 | 제목·본문 입력, 파일 첨부 | 예정 |
| 게시글 수정 | 제목·본문 수정, 첨부파일 관리; 작성 폼 재사용 | 예정 |
| 로그인 | 세션 로그인 | 예정 |
| 회원가입 | 이메일·닉네임·비밀번호 입력 | 예정 |

목록은 실제 API 데이터를 표시하며 예시 게시글을 자동으로 생성하지 않습니다. 글이 없으면 빈 목록 안내를 보여줍니다. 게시글 제목을 누르면 `/post.html?id=게시글ID`로 이동합니다. 상세 화면의 목록 버튼은 기존 검색어·페이지 설정을 유지합니다. 로그인·회원가입·글쓰기·게시글 수정 버튼은 현재 준비 중 안내를 표시합니다.

**상세 디자인 미리보기: http://localhost:8080/post.html?preview=1**

미리보기는 같은 상세 페이지에 예시 본문·첨부파일·댓글을 표시하는 읽기 전용 모드입니다. 실제 API를 호출하거나 DB에 예시 데이터를 저장하지 않으며, 등록·수정·삭제·로그아웃·파일 다운로드도 실행하지 않습니다. 실제 게시글 주소의 조회 실패를 예시 데이터로 대체하지 않습니다.

실제 상세 화면은 로그인 없이 본문·첨부파일·댓글을 조회할 수 있습니다. 같은 브라우저에서 Swagger로 로그인한 세션이 있으면 댓글 작성과 본인 댓글의 수정·삭제, 본인 게시글 삭제도 사용할 수 있습니다. 게시글 삭제 확인창에는 댓글·첨부파일의 동반 삭제를 안내합니다. 게시글 작성·수정과 회원가입·로그인 화면은 아직 준비 중이므로 해당 작업은 Swagger UI에서 실행합니다.

댓글은 등록순으로 10개씩 표시하며, 등록 후 마지막 페이지로 이동합니다. 실패한 댓글 입력은 보존하고, 세션 만료 후 다른 탭에서 로그인했다면 `로그인 상태 확인`으로 초안을 유지한 채 인증 상태를 갱신할 수 있습니다. 변경 요청은 중복 제출을 막고 자동 재시도하지 않습니다. 응답을 받지 못한 경우 `새로고침`으로 댓글 반영 여부를 확인할 수 있습니다.

검색어와 페이지 설정은 URL에 저장해 새로고침·뒤로 가기에도 유지합니다. 로딩 중, 검색 결과 없음, 서버 연결 실패 및 재시도 상태를 구분합니다. 모바일에서는 작성자·작성일을 제목 아래에 표시합니다.

프런트엔드는 별도 빌드 도구 없이 Spring Boot가 제공하는 HTML·CSS·JavaScript를 사용합니다. `src/main/resources/static/assets/css/common.css`의 색상·버튼·입력 요소와 `assets/js/ui.js`의 안내·확인창을 재사용합니다. `api.js`는 세션 쿠키·CSRF·HTTP 오류를 공통 처리합니다. 화면별 스타일·동작은 `board.css`·`board.js`, `post.css`·`post.js`에 분리합니다.

## 기술 스택

- Java 25 / Spring Boot 3.5.16 / Gradle Wrapper 9.1.0
- MariaDB 11.4 / Spring Data JPA
- Spring Security 세션 인증 및 CSRF 보호
- OpenAPI / Swagger UI / Docker Compose
- 테스트: Spring Boot Test, MockMvc, H2 MariaDB 호환 모드

기존 Java 25·Gradle 9.1.0 환경을 유지합니다. Spring Boot 3.5는 Java 25를 지원하지만 공식 Gradle 지원 범위는 7.x(7.6.4 이상)·8.x(8.4 이상)입니다. Java 25로 Gradle을 실행하려면 9.1.0 이상이 필요하므로 현재 조합은 Spring Boot의 공식 Gradle 지원 범위 밖입니다. [Spring Boot 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Gradle Java 호환성](https://docs.gradle.org/current/userguide/compatibility.html)

## Docker Compose 실행

Docker Desktop을 설치하고 Linux 컨테이너 엔진을 실행한 뒤 프로젝트 루트에서 실행합니다. 전체 Compose 실행에는 호스트 JDK가 필요 없습니다.

```powershell
docker compose up --build -d
docker compose ps
docker compose logs -f app
```

MariaDB의 준비 상태를 확인한 뒤 앱이 실행됩니다. 기본 포트는 앱 `8080`, DB `3306`이며 두 포트 모두 호스트의 `127.0.0.1`에만 바인딩합니다. 이 Compose 구성은 로컬 과제용으로, 같은 컴퓨터에서 `localhost` 또는 `127.0.0.1`로 접속합니다. 컨테이너 간 DB 연결은 Compose 내부 네트워크의 `db:3306`을 사용합니다.

기존에 실행한 컨테이너에도 포트 제한을 적용하려면 프로젝트 루트에서 `docker compose up -d`를 다시 실행합니다. 변경된 컨테이너가 재생성되며 기존 DB·업로드 볼륨은 유지됩니다. `docker compose ps`에서 `127.0.0.1:8080`과 `127.0.0.1:3306` 바인딩을 확인할 수 있습니다.

- 게시판 목록: http://localhost:8080/
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- 상세 기능명세: [docs/API_SPEC.md](docs/API_SPEC.md)

종료할 때는 다음 명령을 사용합니다.

```powershell
docker compose down
```

`mariadb-data` 볼륨에 DB가, `uploads-data` 볼륨에 첨부파일이 유지됩니다. 앱 컨테이너를 재생성하거나 `docker compose down`을 실행해도 두 볼륨은 유지됩니다. **DB와 첨부파일을 모두 삭제할 때만** 다음 명령을 실행합니다.

```powershell
docker compose down -v
```

Compose는 `APP_STORAGE_LOCATION=/app/uploads`를 설정하고 해당 경로에 업로드 볼륨을 연결합니다. Docker의 파일 저장소와 IDE 실행 시 사용하는 로컬 `uploads/`는 서로 다른 저장소입니다. DB와 파일을 백업·복원할 때는 두 저장소를 함께 관리합니다.

DB 서버 문자 집합은 한글·이모지 저장을 위해 `utf8mb4`로 지정합니다. 기존 볼륨의 DB·테이블 문자 집합은 이 설정만으로 변경되지 않습니다. Compose의 DB 계정은 로컬 실습용이며 외부 배포 시 별도 계정·비밀번호 설정이 필요합니다. `.dockerignore`는 로컬 캐시, 빌드 결과, 환경 파일, 업로드 파일을 이미지 빌드 입력에서 제외합니다.

Docker Desktop 4.89.0, Docker CLI 29.7.2, Compose 5.5.0 환경에서 이미지 빌드와 앱·MariaDB 11.4.13 실행, Swagger 응답을 확인했습니다. 실제 HTTP 요청으로 회원가입·세션 로그인, 게시글 작성·조회·삭제, 댓글 작성·수정, 한글·이모지 파일명 업로드 및 원본 바이트 다운로드를 검증했습니다. 앱·DB 컨테이너를 `docker compose up -d --force-recreate`로 재생성한 뒤에도 계정·게시글·댓글·첨부파일이 유지됐으며, 게시글 삭제 후 댓글·첨부파일 메타데이터와 실제 파일이 함께 정리되는 것을 확인했습니다.

## 빌드 및 테스트

JDK 25를 설치하고 `JAVA_HOME`을 설정합니다. 별도 Gradle 설치 없이 저장소의 Wrapper를 사용합니다.

Windows PowerShell:

```powershell
.\gradlew.bat clean test bootJar
```

macOS / Linux:

```sh
sh ./gradlew clean test bootJar
```

테스트는 H2와 임시 파일 저장소를 사용하므로 별도 DB나 Docker가 필요 없습니다. 테스트 결과는 `build/reports/tests/test/index.html`, 실행 JAR는 `build/libs/board-0.0.1-SNAPSHOT.jar`에 생성됩니다. H2 테스트만으로 실제 MariaDB와의 완전한 호환성을 검증하지는 않습니다. Compose 이미지 빌드는 JAR를 생성하며, 테스트는 위 Wrapper 명령으로 별도 실행합니다.

검증 결과: `clean test bootJar` 성공, **74개 테스트 통과**. 인증 18개, 게시판 API 30개, 실제 HTTP 흐름 1개, 첨부파일 서비스 7개, 파일 저장소 18개를 검증했습니다. 실제 HTTP 테스트에는 세션 쿠키, 한글 파일 내용, 10 MiB 초과 업로드 거부가 포함됩니다.

목록·상세 화면 추가 후 `test` 재실행 결과 **90개 테스트가 통과**했습니다. 기존 74개에 익명 HTML·정적 리소스·HEAD 접근 및 기존 인증 보호를 확인하는 실제 HTTP 테스트 16개가 추가됐습니다. 공통 API JavaScript는 mocked fetch로 CSRF·세션·오류·취소·시간 제한 등 13개 검증을 통과했습니다. 연결된 브라우저 자동화 환경이 없어 실제 화면 조작 및 시각적 검증은 완료하지 못했습니다.

추가로 JavaScript 모듈 4개의 구문 검사, 목록 복귀 주소 검증 8개를 통과했습니다. 최소 DOM fixture에서 실제 상세 모듈을 실행해 미리보기 콘텐츠 표시, 쓰기 비활성화 및 API 요청 0회도 확인했습니다. 이 검증은 브라우저의 실제 배치·포커스 검증을 대신하지 않습니다.

Windows 한글 경로의 `GradleWorkerMain` 로딩 오류를 방지하도록 `gradle.properties`에 Gradle JVM의 `file.encoding=COMPAT`를 설정했습니다. Java 소스 컴파일과 테스트 JVM은 UTF-8을 사용합니다. Mockito는 테스트 시작 시 명시적인 Java agent로 로딩합니다. IDE에서도 이 설정을 적용하려면 Gradle로 테스트를 실행합니다.

## IDE에서 앱 실행

DB만 Docker로 실행한 뒤 IntelliJ / VS Code에서 `BoardApplication`을 실행하거나 `bootRun`을 사용합니다.

```powershell
docker compose up -d --wait db
.\gradlew.bat bootRun
```

기본 `local` 프로필은 `localhost:3306/board`에 접속하며 사용자명·비밀번호는 모두 `board`입니다. 파일은 기본적으로 실행 디렉터리의 `uploads/`에 저장합니다. 환경 변수 `APP_STORAGE_LOCATION`으로 저장 경로를 변경할 수 있습니다.

Docker 앱과 IDE 앱은 기본 설정에서 같은 DB를 사용하지만 파일 저장소는 다릅니다. 실행 방식을 전환해 기존 첨부파일을 계속 사용하려면 파일도 해당 실행 방식의 저장소로 복사해야 합니다. DB만 공유하면 다른 저장소에 있는 첨부파일의 다운로드가 실패합니다.

전체 Compose 앱과 IDE 앱을 동시에 실행하면 `8080` 포트가 충돌합니다. 전체 Compose를 실행 중이었다면 `docker compose stop app`으로 앱 컨테이너를 먼저 중지합니다.

## Swagger에서 기능 실행

각 API의 `Try it out` → `Execute`를 다음 순서로 실행합니다.

1. `GET /api/auth/csrf`로 CSRF 토큰과 쿠키를 발급받습니다.
2. `POST /api/auth/signup`으로 회원가입합니다. 가입은 자동 로그인하지 않습니다.
3. `POST /api/auth/login`으로 로그인합니다.
4. 로그인 후 `GET /api/auth/csrf`를 다시 호출합니다.
5. `POST /api/posts`로 게시글을 작성하고 응답의 `id`를 확인합니다.
6. 해당 `postId`로 파일 업로드와 댓글 작성을 실행합니다. 목록·상세·다운로드는 로그인 없이도 가능합니다.
7. 작성자 계정으로 게시글을 수정하거나 게시글·파일을 삭제합니다. 댓글 수정·삭제는 댓글 작성자만 가능합니다.
8. `POST /api/auth/logout`으로 로그아웃합니다. 이후 상태 변경 요청을 보내려면 CSRF 토큰을 다시 발급받습니다.

Swagger UI는 `XSRF-TOKEN` 쿠키를 읽어 `X-XSRF-TOKEN` 헤더를 설정합니다. 다른 API 클라이언트도 쿠키를 유지하고 **모든 POST·PUT·DELETE 요청**에 이 헤더를 보내야 합니다. 로그인·로그아웃 시 토큰이 초기화되므로 다시 발급받습니다. 로그인 세션은 `JSESSIONID` 쿠키로 유지되며 유휴 만료 시간은 30분입니다.

회원가입 본문:

```json
{
  "email": "test@example.com",
  "nickname": "tester",
  "password": "password123"
}
```

로그인 본문:

```json
{
  "email": "test@example.com",
  "password": "password123"
}
```

게시글 작성·수정 본문:

```json
{
  "title": "첫 번째 게시글",
  "content": "게시글 본문입니다."
}
```

댓글 작성·수정 본문:

```json
{
  "content": "좋은 글 감사합니다."
}
```

## API 목록과 권한

상태를 변경하는 API에는 표의 로그인·작성자 조건과 별도로 CSRF 토큰이 필요합니다.

| 메서드 | 경로 | 기능 | 권한 | 정상 응답 |
| --- | --- | --- | --- | --- |
| GET | `/api/auth/csrf` | CSRF 토큰·쿠키 발급 | 공개 | 200 |
| POST | `/api/auth/signup` | 회원가입 | 공개 | 201 |
| POST | `/api/auth/login` | 로그인·세션 ID 교체 | 공개 | 200 |
| GET | `/api/auth/me` | 로그인 사용자 조회 | 로그인 | 200 |
| POST | `/api/auth/logout` | 현재 세션 무효화 | CSRF 필요 | 204 |
| GET | `/api/posts` | 게시글 목록·검색 | 공개 | 200 |
| GET | `/api/posts/{postId}` | 게시글 상세 | 공개 | 200 |
| POST | `/api/posts` | 게시글 작성 | 로그인 | 201 |
| PUT | `/api/posts/{postId}` | 제목·본문 수정 | 게시글 작성자 | 200 |
| DELETE | `/api/posts/{postId}` | 게시글·댓글·첨부파일 삭제 | 게시글 작성자 | 204 |
| GET | `/api/posts/{postId}/comments` | 댓글 목록 | 공개 | 200 |
| POST | `/api/posts/{postId}/comments` | 댓글 작성 | 로그인 | 201 |
| PUT | `/api/posts/{postId}/comments/{commentId}` | 댓글 수정 | 댓글 작성자 | 200 |
| DELETE | `/api/posts/{postId}/comments/{commentId}` | 댓글 삭제 | 댓글 작성자 | 204 |
| GET | `/api/posts/{postId}/files` | 첨부파일 목록 | 공개 | 200 |
| POST | `/api/posts/{postId}/files` | 첨부파일 업로드 | 게시글 작성자 | 201 |
| GET | `/api/files/{fileId}/download` | 첨부파일 다운로드 | 공개 | 200 |
| DELETE | `/api/files/{fileId}` | 첨부파일 삭제 | 게시글 작성자 | 204 |

## 입력 제한과 조회 규칙

| 항목 | 규칙 |
| --- | --- |
| 이메일 | 필수, 이메일 형식, 최대 100자, 중복 불가 |
| 닉네임 | 필수, 2~100자 |
| 비밀번호 | 회원가입 시 8자 이상, 가입·로그인 모두 UTF-8 72바이트 이하 |
| 게시글 | 제목 1~200자, 본문 1~10,000자, 공백만 입력 불가 |
| 댓글 | 내용 1~2,000자, 공백만 입력 불가 |
| 목록 페이지 | `page=0`부터 시작, `size=20` 기본값, 크기 1~100 |
| 게시글 검색 | `keyword` 최대 100자, 제목·본문 부분 검색, 대소문자 구분 없음 |
| 파일 | 한 요청에 1~5개, 파일당 최대 10 MiB, 요청 전체 최대 50 MiB |

게시글은 작성 시각·ID 내림차순, 댓글은 작성 시각·ID 오름차순입니다. 게시글 검색어의 앞뒤 공백은 제거하며 빈 검색어는 전체 목록을 반환합니다. `%`와 `_`는 와일드카드가 아닌 문자 그대로 검색합니다.

```text
GET /api/posts?page=0&size=20&keyword=Spring
GET /api/posts/1/comments?page=0&size=20
```

목록은 `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last` 필드를 반환합니다. 파일 목록은 페이징 없는 JSON 배열입니다. 자세한 응답 예시는 [기능명세](docs/API_SPEC.md)를 참고합니다.

## 파일 저장과 삭제

게시글을 먼저 생성한 뒤 `POST /api/posts/{postId}/files`에 `multipart/form-data`의 **`files` 필드**로 파일을 전송합니다. 여러 파일은 같은 필드를 반복합니다. 요청의 50 MiB 제한에는 multipart 헤더·경계 데이터도 포함되므로 10 MiB 파일 5개를 한꺼번에 전송하면 요청 제한을 넘을 수 있습니다.

빈 파일과 잘못된 파일명은 거부합니다. 원본 파일명의 경로 부분을 제거하고 `..` 경로 세그먼트·제어문자·공백뿐인 이름·255자를 넘는 파일명을 허용하지 않습니다. 디스크에는 UUID 이름으로 저장하고 원본 이름은 다운로드 표시용으로 보관합니다. 클라이언트가 지정한 파일명이나 MIME 형식으로 저장 경로·응답 형식을 결정하지 않습니다.

다운로드는 `application/octet-stream`, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`로 응답합니다. 파일은 정적 웹 경로로 직접 공개하지 않고 다운로드 API를 통해 제공합니다.

게시글 삭제 시 댓글은 DB 외래키의 `ON DELETE CASCADE`로 삭제하며 첨부파일 메타데이터도 제거합니다. 실제 파일 삭제는 DB 트랜잭션 커밋 후 수행하고, 업로드 트랜잭션이 실패하면 새 파일을 정리합니다. DB와 파일시스템은 하나의 원자적 트랜잭션이 아니므로 강제 종료나 파일 삭제 실패 시 파일이 남을 수 있습니다. 삭제 실패는 서버 로그에 기록되며 원인을 해결한 뒤 해당 UUID 파일을 별도로 정리해야 합니다.

## 오류 응답

아래의 주요 API 오류는 `{"message":"오류 내용"}` 형태로 응답합니다.

| 상태 | 의미 |
| --- | --- |
| 400 | 입력값·페이지·파일명·파일 개수 오류, 빈 파일, 중복 가입 |
| 401 | 로그인 필요 또는 로그인 실패 |
| 403 | 작성자 권한 없음 또는 CSRF 토큰 누락·불일치 |
| 404 | 게시글·댓글·첨부파일 없음, 댓글이 지정한 게시글에 속하지 않음 |
| 409 | 동시 변경 요청 충돌, 재시도 필요 |
| 413 | 파일 또는 multipart 요청 용량 초과 |
| 500 | 파일 저장소 등 서버 내부 처리 실패 |

상태 변경 요청에서는 CSRF 검증이 로그인 판정보다 먼저 수행되므로 로그인하지 않았고 CSRF 토큰도 없다면 `403`이 먼저 반환될 수 있습니다.

## 제출 자료와 남은 확인

회원 인증, 게시글·댓글 CRUD, 첨부파일 업로드·다운로드 및 Swagger 명세를 제공합니다. 기능명세는 실행 중인 Swagger UI와 [API_SPEC.md](docs/API_SPEC.md)로 확인할 수 있으며 필요하면 해당 문서를 Notion으로 옮길 수 있습니다.

실제 Docker Compose 환경의 실행·데이터 유지 검증과 [개인 GitHub 저장소](https://github.com/4xolotl/Spring-Boot-)의 초기 소스·문서 업로드를 완료했습니다. 나머지 화면을 구현한 뒤 최종 GitHub 링크와 기능명세를 과제로 제출합니다. DB 데이터, 환경 파일, 업로드 파일은 커밋하지 않습니다.
