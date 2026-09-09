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

기본 계정이나 예시 게시글은 자동 생성하지 않습니다. 처음 실행하면 회원가입 후 로그인하여 게시글을 작성합니다.
게시글·댓글·첨부파일 조회는 로그인 없이 이용할 수 있습니다.

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

Docker Desktop을 설치하고 Linux 컨테이너 엔진을 실행합니다. 전체 Compose 실행에는 호스트 JDK가 필요 없습니다.
프로젝트 루트에서 다음 명령을 실행합니다.

```powershell
docker compose up --build -d
docker compose ps
```

DB가 준비되면 앱이 시작됩니다. 시작 상태와 오류는 다음 명령으로 확인합니다.

```powershell
docker compose logs -f app
```

| 접속 대상 | 주소 |
| --- | --- |
| 게시판 | http://localhost:8080/ |
| 회원가입 | http://localhost:8080/signup.html |
| 로그인 | http://localhost:8080/login.html |
| 글쓰기 | http://localhost:8080/write.html |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| 앱 상태 조회 (ADMIN 로그인 필요) | http://localhost:8080/actuator/health |

게시글 상세·수정 화면은 목록과 상세 화면의 버튼으로 이동합니다.
앱 `8080`과 DB `3306` 포트는 호스트의 `127.0.0.1`에만 바인딩하며 같은 컴퓨터에서 접속합니다.
해당 포트를 다른 프로그램이 사용 중이면 먼저 포트 충돌을 해소합니다.

### Actuator 관리자 접근

`health`, `info`, `metrics`, `mappings`는 `ADMIN` 권한으로 로그인한 세션에서만 조회할 수 있습니다.
비로그인 요청은 `401`, 일반 회원 요청은 `403`을 반환합니다.
신규·기존 회원의 기본 권한은 `USER`이며, 관리자는 자동 생성하지 않습니다.
DB 관리자가 대상 회원의 `users.role`을 `ADMIN`으로 지정한 뒤 해당 회원이 다시 로그인하면 적용됩니다. Google 로그인도 같은 로컬 계정 권한을 사용합니다.
권한을 회수할 때는 `USER`로 변경하고 앱을 재시작해 기존 로그인 세션도 종료합니다.

### Google 로그인 설정 (선택)

Google 로그인은 기본 비활성화입니다. 설정하지 않아도 이메일·비밀번호로 가입하고 로그인할 수 있습니다.
사용하려면 Google OAuth 클라이언트를 준비하고, 프로젝트 루트에 `.env`가 없을 때 [.env.example](.env.example)을 `.env`로 복사해 다음 값을 설정합니다.

| 변수 | 값 |
| --- | --- |
| `GOOGLE_LOGIN_ENABLED` | `true` |
| `GOOGLE_CLIENT_ID` | 발급받은 Client ID |
| `GOOGLE_CLIENT_SECRET` | 발급받은 Client Secret |
| `GOOGLE_REDIRECT_URI` | `http://localhost:8080/login/oauth2/code/google` |

Google에 등록한 승인된 리디렉션 URI는 위 주소와 정확히 일치해야 합니다.
`.env`는 Git에 포함하지 않습니다. 설정을 변경한 뒤 앱을 다시 실행합니다.

```powershell
docker compose up -d app
```

### 종료와 데이터 유지

잠시 중지할 때는 다음 명령을 사용합니다.

```powershell
docker compose stop
```

컨테이너와 Compose 네트워크를 제거할 때는 다음 명령을 사용합니다.

```powershell
docker compose down
```

DB는 `mariadb-data`, 첨부파일은 `uploads-data` 볼륨에 저장됩니다.
위 명령이나 앱 컨테이너 재생성으로 두 볼륨의 데이터가 삭제되지는 않습니다. 다시 시작할 때는 `docker compose up -d`를 실행합니다.

## 사용 범위와 기능명세

- 게시글 제목은 1\~200자, 본문은 1\~10,000자, 댓글은 1\~2,000자입니다.
- 파일은 요청당 최대 5개, 파일당 10 MiB, 전체 multipart 요청은 50 MiB까지입니다.
- 글·댓글 수정 및 파일 처리의 요청 형식과 응답 코드는 [API 기능명세](docs/API_SPEC.md)에서 확인합니다.
- Swagger UI에서는 회원가입·로그인 후 같은 브라우저의 세션으로 API를 실행합니다.
- Actuator는 `health`, `info`, `metrics`, `mappings`의 조회를 제공하며 구성과 접근 범위는 기능명세에 기재되어 있습니다.

## 로컬 빌드 (선택)

JDK 25와 `JAVA_HOME`을 준비한 뒤 Gradle Wrapper를 실행합니다.

Windows PowerShell:

```powershell
.\gradlew.bat clean test bootJar
```

macOS / Linux:

```sh
sh ./gradlew clean test bootJar
```

실행 JAR는 `build/libs/board-0.0.1-SNAPSHOT.jar`에 생성됩니다.
Gradle 9.1.0은 현재 프로젝트의 Java 25 실행 조합이며, Spring Boot 3.5의 [공식 Gradle 지원 범위](https://docs.spring.io/spring-boot/3.5/system-requirements.html)에는 포함되지 않습니다.
