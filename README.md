# Spring Boot 게시판

세션 기반 사용자 인증, 게시글·댓글 관리와 첨부파일 업로드·다운로드를 제공하는 게시판 프로젝트입니다.
웹 화면과 Swagger UI에서 기능을 이용할 수 있습니다. 상세 요청·응답은 [API 기능명세](docs/API_SPEC.md)를 참고합니다.

## 주요 기능과 화면

| 화면 | 기능 |
| --- | --- |
| 게시글 목록 | 제목·본문 검색, 최신순 목록, 페이지 이동 |
| 게시글 상세 | 본문·첨부파일 조회, 댓글 작성·수정·삭제 |
| 게시글 작성 | 제목·본문 입력, 파일 동시 첨부 |
| 게시글 수정 | 제목·본문 수정, 첨부파일 추가·삭제 |
| 로그인 | 이메일·비밀번호 로그인, Google 로그인, 로그아웃 |
| 회원가입 | 이메일·닉네임·비밀번호 등록, 가입 후 로그인 연결 |

게시글·댓글·첨부파일은 로그인 없이 조회할 수 있으며, 글 작성에는 로그인이 필요합니다.

## 기술 스택

| 구분 | 사용 기술 |
| --- | --- |
| 언어·프레임워크 | Java 25, Spring Boot 3.5.16 |
| 빌드 | Gradle Wrapper 9.1.0 |
| 데이터베이스 | MariaDB 11.4, Spring Data JPA, Flyway |
| 인증 | Spring Security 세션 인증, Google OAuth2 Login·OIDC |
| 웹 화면 | HTML, CSS, JavaScript |
| API·상태 조회 | OpenAPI, Swagger UI, Spring Boot Actuator |
| 실행 환경 | Docker, Docker Compose |

## Docker Compose 실행

Docker Desktop의 Linux 컨테이너 엔진을 실행합니다. 별도의 JDK 설치는 필요하지 않습니다.

### Google 로그인 설정 (선택)

Google 로그인은 기본 비활성화이며, 이메일·비밀번호 로그인은 별도 설정 없이 사용할 수 있습니다.
Google 로그인을 사용하려면 실행 전에 [.env.example](.env.example)을 참고해 프로젝트 루트의 `.env`에 다음 값을 설정합니다.

| 변수 | 값 |
| --- | --- |
| `GOOGLE_LOGIN_ENABLED` | `true` |
| `GOOGLE_CLIENT_ID` | 발급받은 Client ID |
| `GOOGLE_CLIENT_SECRET` | 발급받은 Client Secret |
| `GOOGLE_REDIRECT_URI` | `http://localhost:8080/login/oauth2/code/google` |

Google에 등록한 승인된 리디렉션 URI는 위 주소와 정확히 일치해야 합니다.

### 실행과 접속

프로젝트 루트에서 실행합니다.

```powershell
docker compose up --build -d
```

| 접속 대상 | 주소 |
| --- | --- |
| 게시판 | http://localhost:8080/ |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| 앱 상태 조회 (ADMIN 로그인 필요) | http://localhost:8080/actuator/health |

앱 `8080`과 DB `3306` 포트는 `127.0.0.1`에 바인딩되어 로컬 컴퓨터에서만 접속할 수 있습니다.

### 중지와 데이터 보관

| 명령 | 동작 |
| --- | --- |
| `docker compose stop` | 컨테이너 중지 |
| `docker compose up -d` | 다시 실행 |
| `docker compose down` | 컨테이너와 Compose 네트워크 제거 |

DB와 첨부파일은 각각 `mariadb-data`, `uploads-data` 볼륨에 저장되며, 위 명령으로 컨테이너를 중지하거나 제거해도 유지됩니다.

## Actuator 관리자 접근

`health`, `info`, `metrics`, `mappings`는 `ADMIN` 권한으로 로그인한 계정만 조회할 수 있습니다.
이메일·Google 로그인 모두 동일한 로컬 계정 권한을 사용합니다.

- 회원의 기본 권한은 `USER`입니다.
- DB 관리자가 대상 회원의 `users.role`을 `ADMIN`으로 지정하고, 해당 회원이 다시 로그인하면 적용됩니다.
- 권한을 회수할 때는 `USER`로 변경하고 앱을 재시작해 기존 로그인 세션을 종료합니다.

## 사용 조건

- 게시글 제목은 1\~200자, 본문은 1\~10,000자, 댓글은 1\~2,000자입니다.
- 파일은 요청당 최대 5개, 파일당 10 MiB, 전체 multipart 요청은 50 MiB까지입니다.
- Swagger UI에서는 같은 브라우저의 로그인 세션으로 API를 실행합니다.
