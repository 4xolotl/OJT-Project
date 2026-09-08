# Spring Boot 게시판

세션 기반 사용자 인증, 게시글·댓글 관리, 첨부파일 업로드·다운로드를 제공하는 게시판 과제 프로젝트입니다. **게시글 목록·상세·작성·수정·로그인·회원가입의 기본 6개 화면**을 구현했으며, 전체 API는 Swagger UI에서도 실행할 수 있습니다.

요청·응답과 권한별 동작은 [API 기능명세](docs/API_SPEC.md)에서 확인할 수 있습니다.

## 웹 화면

접속 주소: **http://localhost:8080/**

| 화면 | 포함 기능 | 구현 상태 |
| --- | --- | --- |
| 게시글 목록 | 제목·본문 검색, 최신순 목록, 페이지 이동, 페이지 크기 변경 | 구현 |
| 게시글 상세 | 본문, 첨부파일 다운로드, 댓글 작성·수정·삭제, 작성자의 글 수정 연결·삭제 | 구현 |
| 게시글 작성 | 제목·본문 입력, 파일 선택·제거, 글과 파일 함께 등록 | 구현 |
| 게시글 수정 | 제목·본문 변경, 기존 첨부파일 삭제 선택·신규 파일 추가, 충돌 확인 | 구현 |
| 로그인 | 이메일·비밀번호, Google 로그인, 비밀번호 보기, 기존 화면 복귀 | 구현; Google 사용은 별도 설정 필요 |
| 회원가입 | 이메일·닉네임·비밀번호·비밀번호 확인, 가입 후 로그인 연결 | 구현 |

목록은 실제 API 데이터를 표시하며 예시 게시글을 자동으로 생성하지 않습니다. 글이 없으면 빈 목록 안내를 보여줍니다. 게시글 제목을 누르면 `/post.html?id=게시글ID`로 이동합니다. 상세 화면의 목록 버튼은 기존 검색어·페이지 설정을 유지합니다. 목록 상단의 `글쓰기`와 빈 목록의 `첫 글 쓰기`는 작성 화면으로 이동합니다. 작성자에게만 표시되는 상세의 `수정`은 `/edit.html?id=게시글ID`로 이동합니다. 댓글 초안이 있을 때 같은 탭에서 이동하면 입력 내용이 사라지는지 확인합니다.

**글쓰기: http://localhost:8080/write.html**

제목은 1\~200자, 본문은 1\~10,000자이며 공백만 입력할 수 없습니다. 입력한 원문을 전송하고 HTML 태그는 실행하지 않습니다. 파일은 선택 사항이며 최대 5개, 파일당 10 MiB, 제목·본문과 multipart 부가 데이터를 포함한 전체 요청은 50 MiB까지 허용합니다. 등록 시 `POST /api/posts` 한 번으로 제목·본문의 JSON `post` 파트와 선택한 `files` 파트를 함께 전송합니다. 성공하면 작성된 글의 상세 화면으로 이동하고 기존 검색·페이지 조건을 유지합니다.

비회원도 내용을 입력하고 파일을 선택할 수 있으며 등록에는 로그인이 필요합니다. 작성 화면의 로그인·회원가입은 새 탭에서 열립니다. 돌아오거나 `로그인 상태 다시 확인`을 누르면 제목·본문·파일 선택을 유지한 채 인증 상태를 갱신합니다. 초안은 현재 탭에만 남으며 새로고침하거나 탭을 닫으면 사라집니다. 취소 시 입력 내용이 있으면 이탈을 확인합니다.

실패한 요청은 자동 재시도하지 않습니다. 응답을 받지 못했거나 등록 결과가 불확실하면 재등록을 막고, 새 탭에서 목록을 확인하도록 안내합니다. 등록되지 않았음을 확인한 뒤 다시 작성할 수 있습니다. 첨부 저장 실패 시 게시글·첨부 메타데이터를 롤백하고 저장된 파일 정리를 시도하지만, DB와 파일시스템은 하나의 원자적 트랜잭션이 아니므로 강제 종료·정리 실패 시 파일이 남을 수 있습니다.

**게시글 수정: http://localhost:8080/edit.html?id=게시글ID**

작성자만 제목·본문을 수정하고 기존 첨부파일의 삭제를 선택하거나 새 파일을 추가할 수 있습니다. 삭제 선택은 저장할 때 반영되며, 글과 첨부파일 변경을 multipart `PUT /api/posts/{postId}` 한 요청으로 전송합니다. 신규 파일은 요청당 최대 5개, 파일당 10 MiB이며 전체 요청은 50 MiB까지입니다. 기존 첨부파일을 포함한 총 개수를 5개로 제한하지는 않습니다.

처음 읽은 글의 `updatedAt`과 첨부파일 ID 목록을 함께 보내 다른 화면에서 바뀐 내용을 덮어쓰지 않도록 확인합니다. 변경 충돌 `409`가 발생하면 입력·선택 파일을 유지하고, 사용자가 명시적으로 최신 내용을 다시 불러오도록 안내합니다. 최신 내용을 불러오면 현재 수정 초안은 교체됩니다. 로그인 만료 시 새 탭에서 로그인하고 돌아와 초안을 유지한 채 상태를 갱신할 수 있습니다. 저장·취소 후에는 기존 검색·페이지 조건을 포함한 상세 화면으로 돌아갑니다. 수정 화면에는 예시 미리보기를 제공하지 않습니다.

응답을 받지 못해 저장 결과가 불확실할 때도 자동 재전송하지 않습니다. 입력을 복사하거나 계속 정리할 수 있지만 저장은 막고, 현재 게시글을 확인한 뒤 최신 내용을 다시 불러오도록 안내합니다. 최신 조회에 실패하면 초안·파일 선택을 유지합니다. 초안은 현재 탭에만 남으며 새로고침·탭 닫기에는 보존되지 않습니다. 본문·제목을 건드리지 않고 파일만 수정한 경우 기존 글의 원문을 그대로 전송합니다.

**로그인: http://localhost:8080/login.html**

목록·상세의 로그인 버튼에서 이동하면 로그인 후 기존 화면의 검색어·페이지 설정을 유지해 돌아옵니다. 복귀 주소는 게시판 목록·작성 화면과 유효한 상세·수정 주소만 허용합니다. 이메일·비밀번호 입력 검증, 비밀번호 보기, 로그인 실패 안내와 중복 제출 방지를 제공합니다. 이미 로그인한 상태에서는 닉네임과 돌아가기·계정 전환 버튼을 표시합니다. 비밀번호는 URL이나 웹 저장소에 저장하지 않으며, 로그인 성공 또는 화면 이탈 시 입력창에서 지웁니다.

`Google로 계속하기`는 Google OIDC Authorization Code 로그인으로 연결됩니다. 최초 Google 로그인은 게시판 회원을 생성하며 이후 일반 로그인과 같은 세션·게시판 권한을 사용합니다. 동일 이메일의 일반 계정은 자동 연결하지 않고 기존 비밀번호 로그인을 안내합니다. Google 로그인은 기본 비활성화이며 준비 중·연결 조회 실패 상태에서도 이메일 로그인은 사용할 수 있습니다. Google 버튼을 누르면 비밀번호 입력을 비우고 중복 로그인을 막습니다.

Google 자격 증명은 저장소에 포함하지 않으며 실행 환경에 별도로 설정합니다.

**회원가입: http://localhost:8080/signup.html**

계정이 없다면 목록·상세 또는 로그인 화면의 `회원가입` 링크에서 가입할 수 있습니다. 이메일, 닉네임, 비밀번호와 비밀번호 확인을 입력하며 길이·허용 문자·이메일 형식·비밀번호 일치를 검사합니다. 비밀번호는 8\~72자이며 영문 대소문자·숫자·ASCII 특수문자만 사용할 수 있습니다. 허용 특수문자는 입력란 아래에서 펼쳐 볼 수 있습니다. 중복 이메일과 서버 오류는 폼 위에 안내합니다. 실패 시 입력을 유지하고 자동 재시도하지 않습니다. 응답을 받지 못했을 때는 가입이 완료되었을 수 있으므로 로그인 화면에서 먼저 확인합니다.

가입 성공 후 비밀번호 두 입력창을 비우고 로그인 화면에 가입 완료 안내를 표시합니다. 자동 로그인하지 않으며 가입한 이메일과 비밀번호로 로그인합니다. 회원가입·로그인을 거쳐도 기존 게시판 복귀 주소를 유지합니다. 비밀번호 확인 값은 서버에 전송하지 않고, 비밀번호를 URL·웹 저장소에 기록하지 않습니다. 예시 계정은 자동 생성하지 않습니다.

**상세 디자인 미리보기: http://localhost:8080/post.html?preview=1**

미리보기는 같은 상세 페이지에 예시 본문·첨부파일·댓글을 표시하는 읽기 전용 모드입니다. 실제 API를 호출하거나 DB에 예시 데이터를 저장하지 않으며, 등록·수정·삭제·로그아웃·파일 다운로드도 실행하지 않습니다. 실제 게시글 주소의 조회 실패를 예시 데이터로 대체하지 않습니다.

실제 상세 화면은 로그인 없이 본문·첨부파일·댓글을 조회할 수 있습니다. 로그인하면 댓글 작성과 본인 댓글의 수정·삭제, 본인 게시글의 수정 화면 연결·삭제도 사용할 수 있습니다. 목록·상세 상단에는 로그인한 닉네임과 로그아웃 버튼이 표시됩니다. 게시글 삭제 확인창에는 댓글·첨부파일의 동반 삭제를 안내합니다.

댓글은 등록순으로 10개씩 표시하며, 등록 후 마지막 페이지로 이동합니다. 실패한 댓글 입력은 보존합니다. 세션 만료 후 작성·수정 중인 댓글이 있으면 로그인·회원가입 링크가 새 탭에서 열리고, 원래 탭으로 돌아오면 입력 내용을 유지한 채 인증 상태를 갱신합니다. `로그인 상태 확인` 버튼으로도 갱신할 수 있습니다. 초안은 현재 탭의 입력창에만 남으므로 탭을 닫거나 페이지를 새로고침하면 사라집니다. 변경 요청은 중복 제출을 막고 자동 재시도하지 않습니다. 응답을 받지 못한 경우 댓글 영역의 `새로고침`으로 반영 여부를 확인할 수 있습니다.

검색어와 페이지 설정은 URL에 저장해 새로고침·뒤로 가기에도 유지합니다. 로딩 중, 검색 결과 없음, 서버 연결 실패 및 재시도 상태를 구분합니다. 모바일에서는 작성자·작성일을 제목 아래에 표시합니다.

프런트엔드는 별도 빌드 도구 없이 Spring Boot가 제공하는 HTML·CSS·JavaScript를 사용합니다. `src/main/resources/static/assets/css/common.css`의 색상·버튼·입력 요소와 `assets/js/ui.js`의 안내·확인창을 재사용합니다. `api.js`는 세션 쿠키·CSRF·JSON 및 multipart 요청·HTTP 오류를, `navigation.js`는 화면 간 이동과 안전한 복귀 주소를 공통 처리합니다. 목록은 `board.css`·`board.js`, 상세는 `post.css`·`post.js`로 나눕니다. 작성·수정은 `editor.css`와 `editor-fields.js`의 입력·파일 검증을 공유하고 `write.js`·`edit.js`에서 각 저장 흐름을 처리합니다. 로그인·회원가입은 `auth.css`를 공유하며 `login.js`·`signup.js`에서 동작을 처리합니다.

## 기술 스택

- Java 25 / Spring Boot 3.5.16 / Gradle Wrapper 9.1.0
- MariaDB 11.4 / Spring Data JPA / Flyway 스키마 마이그레이션
- Spring Security 세션 인증·CSRF 보호 / Google OAuth2 Login·OIDC
- Spring Boot Actuator 상태 확인 (`/actuator/health`)
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

앱 시작 시 Flyway가 `src/main/resources/db/migration`의 스키마 변경을 적용하고, Hibernate는 `ddl-auto=validate`로 매핑을 확인합니다. 새 DB는 V1의 게시판 테이블을 만든 뒤 V2를 적용합니다. 기존 게시판 DB는 V1을 기준으로 기록한 뒤 V2에서 사용자 이메일을 254자로 확장하고 비밀번호 NULL 허용·Google 계정 연결 테이블을 추가합니다. 기존 회원·게시글 데이터는 유지하며, 기존 DB를 다른 스키마로 임의 초기화하는 용도로 사용하지 않습니다. DB 변경 전에는 백업을 준비합니다.

기존에 실행한 컨테이너에도 포트 제한을 적용하려면 프로젝트 루트에서 `docker compose up -d`를 다시 실행합니다. 변경된 컨테이너가 재생성되며 기존 DB·업로드 볼륨은 유지됩니다. `docker compose ps`에서 `127.0.0.1:8080`과 `127.0.0.1:3306` 바인딩을 확인할 수 있습니다.

- 게시판 목록: http://localhost:8080/
- 글쓰기: http://localhost:8080/write.html
- 글 수정: 상세 화면에서 작성자의 `수정` 선택 (`/edit.html?id=게시글ID`)
- 로그인: http://localhost:8080/login.html
- 회원가입: http://localhost:8080/signup.html
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- 상세 기능명세: [docs/API_SPEC.md](docs/API_SPEC.md)
- Actuator 상태 확인: http://localhost:8080/actuator/health

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

Google 로그인을 사용할 때는 `.env.example`을 참고해 Git에 포함되지 않는 로컬 `.env`에 `GOOGLE_LOGIN_ENABLED=true`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`를 설정하고 앱을 재생성합니다. 콜백 기본값은 `http://localhost:8080/login/oauth2/code/google`이며 Google에 등록한 주소와 정확히 일치해야 합니다. 미설정 상태에서는 기존 일반 로그인을 사용합니다.

Docker Desktop 4.89.0, Docker CLI 29.7.2, Compose 5.5.0 환경에서 이미지 빌드와 앱·MariaDB 11.4.13 실행, Swagger 응답을 확인했습니다. 실제 HTTP 요청으로 회원가입·세션 로그인, 게시글 작성·조회·삭제, 댓글 작성·수정, 한글·이모지 파일명 업로드 및 원본 바이트 다운로드를 검증했습니다. 앱·DB 컨테이너를 `docker compose up -d --force-recreate`로 재생성한 뒤에도 계정·게시글·댓글·첨부파일이 유지됐으며, 게시글 삭제 후 댓글·첨부파일 메타데이터와 실제 파일이 함께 정리되는 것을 확인했습니다.

## Actuator 상태 확인

`spring-boot-starter-actuator`를 추가하고 `application.yml`과 `SecurityConfig`에 관리용 경로의 노출·접근 규칙을 설정했습니다. 게시판 화면·서비스·DB 스키마 변경 없이 기존 `8080` 포트에서 사용합니다.

```powershell
curl.exe -i http://localhost:8080/actuator/health
```

정상 상태는 HTTP `200`과 `{"status":"UP"}`입니다. 상태 검사에서 `DOWN` 또는 `OUT_OF_SERVICE`로 판정하면 HTTP `503`과 해당 `status`를 응답합니다. DB 연결과 서버 작업 디렉터리의 디스크 여유 공간을 확인하며, `UP`이 파일 업로드·Google 로그인 등 모든 기능의 정상 동작을 보장하지는 않습니다.

로그인 없이 정확한 `GET /actuator/health`와 `HEAD /actuator/health`만 사용할 수 있습니다. DB·디스크의 구성 요소와 세부 정보는 로그인 여부와 관계없이 응답에서 숨깁니다. `env`, `configprops`, `heapdump`, `loggers`, `shutdown`, `metrics`, `info` 등 다른 엔드포인트와 JMX 노출은 허용하지 않습니다. `/actuator` 목록과 `/actuator/health/db` 등 하위 경로도 차단합니다. 차단한 경로는 기존 인증 처리에 따라 비회원은 `401`, 로그인 사용자는 `403`을 받습니다. 상태 변경 요청의 CSRF 보호도 유지합니다.

Actuator 설치만으로 Docker의 앱 healthcheck나 자동 장애 복구가 연결되지는 않습니다. 현재 Compose의 healthcheck는 DB용이며, 앱 상태 조회는 위 URL로 직접 확인합니다.

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

전체 Java 테스트 **326개가 통과**했습니다(실패·오류·건너뜀 0). 세션 인증·권한·CSRF·입력 검증, 게시글·댓글·파일 변경과 롤백, 수정 충돌, 오류 응답·로그의 비밀번호 비노출을 검증합니다. OIDC는 테스트용 제공자 서버로 PKCE·state·nonce·토큰 검증과 세션 연결을 확인합니다. Actuator는 상태 응답·접근 제한·상세 정보 비공개와 격리된 검사기의 장애·복구를 검증합니다.

JavaScript 검증 **209개가 통과**했습니다. 실제 모듈을 Node VM과 최소 DOM/API fixture에서 실행해 입력·파일 검증, 초안 보존, 권한·변경 충돌·응답 불확실성, 중복 제출과 안전한 복귀를 확인합니다. 실제 브라우저의 배치·파일 선택창·BFCache 진입 자체는 자동 검증 범위가 아니며 최종 수동 검수가 필요합니다. Node.js가 설치되어 있다면 프로젝트 루트에서 다음 명령을 실행합니다. 앱 실행이나 Gradle 테스트에는 Node.js가 필요하지 않습니다.

```sh
node --experimental-vm-modules src/test/js/navigation.test.mjs
node --experimental-vm-modules src/test/js/login.test.mjs
node --experimental-vm-modules src/test/js/signup.test.mjs
node --experimental-vm-modules src/test/js/password-policy.test.mjs
node --experimental-vm-modules src/test/js/api.test.mjs
node --experimental-vm-modules src/test/js/write.test.mjs
node --experimental-vm-modules src/test/js/edit.test.mjs
node --experimental-vm-modules src/test/js/post.test.mjs
```

Docker·MariaDB에서는 실제 HTTP로 일반 인증, 글·댓글·파일 처리, 한글·이모지 파일명과 원본 바이트 다운로드, 충돌 `409`, 빈 파일 `400`, Tomcat 용량 제한 `413`과 실패 시 데이터 보존을 확인했습니다. 새 DB와 기존 DB의 Flyway 적용, 컨테이너 재생성 후 데이터·파일 유지도 검증했습니다. Actuator 적용 후에는 상태·접근 차단과 기존 공개 화면/API 조회를 확인했습니다.

실제 Google 로그인은 별도의 수동 확인을 완료했습니다. Google 로그인 왕복과 브라우저 전체 조작의 자동 검증, 실행 중인 Docker DB를 중지하는 장애 실험은 위 검증 범위에 포함하지 않습니다.

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
| GET | `/api/auth/providers` | Google 로그인 활성화 여부 | 공개 | 200 |
| POST | `/api/auth/signup` | 회원가입 | 공개 | 201 |
| POST | `/api/auth/login` | 로그인·세션 ID 교체 | 공개 | 200 |
| GET | `/api/auth/me` | 로그인 사용자 조회 | 로그인 | 200 |
| POST | `/api/auth/logout` | 현재 세션 무효화 | CSRF 필요 | 204 |
| GET | `/api/posts` | 게시글 목록·검색 | 공개 | 200 |
| GET | `/api/posts/{postId}` | 게시글 상세 | 공개 | 200 |
| POST | `/api/posts` | 게시글 작성; JSON 또는 파일 동시 첨부 multipart | 로그인 | 201 |
| PUT | `/api/posts/{postId}` | JSON 제목·본문 수정 또는 multipart 글·첨부 동시 수정 | 게시글 작성자 | 200 |
| DELETE | `/api/posts/{postId}` | 게시글·댓글·첨부파일 삭제 | 게시글 작성자 | 204 |
| GET | `/api/posts/{postId}/comments` | 댓글 목록 | 공개 | 200 |
| POST | `/api/posts/{postId}/comments` | 댓글 작성 | 로그인 | 201 |
| PUT | `/api/posts/{postId}/comments/{commentId}` | 댓글 수정 | 댓글 작성자 | 200 |
| DELETE | `/api/posts/{postId}/comments/{commentId}` | 댓글 삭제 | 댓글 작성자 | 204 |
| GET | `/api/posts/{postId}/files` | 첨부파일 목록 | 공개 | 200 |
| POST | `/api/posts/{postId}/files` | 첨부파일 업로드 | 게시글 작성자 | 201 |
| GET | `/api/files/{fileId}/download` | 첨부파일 다운로드 | 공개 | 200 |
| DELETE | `/api/files/{fileId}` | 첨부파일 삭제 | 게시글 작성자 | 204 |

Google 로그인은 브라우저에서 `GET /oauth2/authorization/google?returnTo=...`로 시작하며, Google의 응답은 `GET /login/oauth2/code/google`로 처리합니다. Swagger에서 토큰을 붙여 넣는 방식이 아닙니다. 성공 후 `GET /api/auth/me` 및 기존 게시판 API는 같은 세션 쿠키를 사용합니다.

## 입력 제한과 조회 규칙

| 항목 | 규칙 |
| --- | --- |
| 일반 가입 이메일 | 필수, 이메일 형식, 최대 100자, 중복 불가 |
| 일반 가입 닉네임 | 필수, 2\~100자 |
| 비밀번호 | 가입 8\~72자, 로그인 1\~72자; 영문 대소문자·숫자·ASCII 특수문자만 허용, 공백·한글·이모지·제어문자 금지 |
| 게시글 | 제목 1\~200자, 본문 1\~10,000자, 공백만 입력 불가 |
| 댓글 | 내용 1\~2,000자, 공백만 입력 불가 |
| 목록 페이지 | `page=0`부터 시작, `size=20` 기본값, 크기 1\~100 |
| 게시글 검색 | `keyword` 최대 100자, 제목·본문 부분 검색, 대소문자 구분 없음 |
| 파일 | 한 요청에 1\~5개, 파일당 최대 10 MiB, 요청 전체 최대 50 MiB |

비밀번호의 허용 범위는 ASCII `U+0021`\~`U+007E`입니다. 영문·숫자와 아래 특수문자 32개를 허용하며, 문자 종류별 필수 혼합 조건은 두지 않습니다.

```text
!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~
```

화면과 서버가 같은 문자·길이 규칙을 검증하며, 비밀번호의 앞뒤 공백을 지우거나 금지 문자를 치환하지 않습니다. 길이가 넘는 값을 자르지 않고 거부하며, 공백·줄바꿈 등 금지 문자가 포함된 붙여넣기·드롭은 해당 입력을 취소하고 안내합니다. 허용된 비밀번호의 붙여넣기와 비밀번호 관리자 자동완성은 유지합니다. ASCII는 한 글자가 한 바이트이므로 72자 상한이 BCrypt의 72바이트 상한과 일치하며, 서버의 기존 바이트 제한 검증도 유지합니다. 비밀번호는 BCrypt 해시로 저장하고 응답·오류에 원문을 포함하지 않습니다.

이전 정책에서 한글·공백 등 현재 허용되지 않는 문자로 만든 테스트 계정은 새 정책에서 로그인할 수 없습니다. 저장된 해시를 새 비밀번호로 변환하지 않으며, 필요한 경우 허용된 비밀번호와 다른 이메일로 테스트 계정을 새로 가입합니다.

따옴표·역슬래시·세미콜론 등은 SQL 구문에 사용되지만 비밀번호 원문을 SQL에 연결하지 않습니다. 가입 시 BCrypt로 변환한 해시를 JPA의 바인딩으로 저장하고, 로그인은 이메일로 조회한 해시를 Java의 비밀번호 검증기로 비교합니다. 실제 MariaDB의 `password` 컬럼은 `varchar(255)`·`utf8mb4`이며 저장된 BCrypt 값은 60자입니다. 컬럼의 대소문자 비구분 정렬 규칙은 이 Java 비밀번호 비교에 적용되지 않습니다. 요청은 `JSON.stringify`로 직렬화하고, 비밀번호를 HTML이나 쉘 명령으로 실행하는 경로도 두지 않습니다. SQL 방어는 파라미터 바인딩을 유지하는 것이 핵심입니다. [OWASP SQL Injection Prevention](https://cheatsheetseries.owasp.org/cheatsheets/SQL_Injection_Prevention_Cheat_Sheet.html)

Spring MVC는 DEBUG/TRACE 로그에서 요청 DTO나 검증 실패 값을 기록할 수 있으므로 공통 설정에 `logging.level.org.springframework.web: INFO`를 명시합니다. 인증을 처리하는 환경에서 해당 로그 수준을 DEBUG/TRACE로 다시 올리지 않습니다. 이 설정은 애플리케이션 코드의 비밀번호 로그 금지와 함께 유지합니다.

Google 인증 코드·state·제공자 토큰이 인증 처리 로그에 남지 않도록 `logging.level.org.springframework.security: INFO`도 유지합니다. Google 회원은 비밀번호 없이 저장하며 확인된 이메일은 최대 254자입니다. 일반 가입의 입력 제한은 위 표와 같습니다.

로그 설정 반영 후 Docker 앱에서도 잘못된 로그인 요청이 `400`을 반환하고 임시 비밀번호 표식이 앱 로그에 남지 않는 것을 확인했습니다. 이 검증은 계정을 생성하거나 기존 데이터를 변경하지 않습니다.

게시글은 작성 시각·ID 내림차순, 댓글은 작성 시각·ID 오름차순입니다. 게시글 검색어의 앞뒤 공백은 제거하며 빈 검색어는 전체 목록을 반환합니다. `%`와 `_`는 와일드카드가 아닌 문자 그대로 검색합니다.

```text
GET /api/posts?page=0&size=20&keyword=Spring
GET /api/posts/1/comments?page=0&size=20
```

목록은 `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last` 필드를 반환합니다. 파일 목록은 페이징 없는 JSON 배열입니다. 자세한 응답 예시는 [기능명세](docs/API_SPEC.md)를 참고합니다.

## 파일 저장과 삭제

글쓰기 화면은 `POST /api/posts`에 `multipart/form-data`로 **`post` 파트**(`application/json`의 제목·본문)와 선택적인 **`files` 파트**를 함께 보냅니다. 응답은 파일 목록이 아닌 생성한 게시글의 `PostResponse`(`201`)입니다. 기존 JSON 게시글 작성도 지원하며, 생성 후 `POST /api/posts/{postId}/files`로 파일만 추가할 수도 있습니다. 여러 파일은 `files` 파트를 반복합니다. 요청의 50 MiB 제한에는 제목·본문과 multipart 헤더·경계 데이터도 포함되므로 10 MiB 파일 5개를 한꺼번에 전송하면 요청 제한을 넘을 수 있습니다.

수정은 multipart `PUT /api/posts/{postId}`의 `post` 파트에 `title`, `content`, 원래 `updatedAt`, 원래 전체 `attachmentIds`, 삭제할 `deletedFileIds`를 넣고 신규 `files`를 선택적으로 보냅니다. 두 ID 배열은 필수이며 빈 배열은 허용합니다. 각각 최대 1,000개이고 삭제 목록은 원래 목록의 부분집합이어야 합니다. 응답은 갱신된 `PostResponse`(`200`)입니다. 기존 `{title,content}` JSON 수정 API도 유지합니다. 자세한 요청 예시와 충돌 규칙은 [API_SPEC.md](docs/API_SPEC.md#글과-첨부파일-동시-수정)에 있습니다.

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
| 409 | 동시 변경 요청 충돌; 수정 시각·첨부파일 집합을 최신 내용과 다시 확인 |
| 413 | 파일 또는 multipart 요청 용량 초과 |
| 500 | 파일 저장소 등 서버 내부 처리 실패 |

상태 변경 요청에서는 CSRF 검증이 로그인 판정보다 먼저 수행되므로 로그인하지 않았고 CSRF 토큰도 없다면 `403`이 먼저 반환될 수 있습니다.

## 검토용 문서

기능명세는 실행 중인 Swagger UI와 [API_SPEC.md](docs/API_SPEC.md)에서 확인할 수 있습니다.

저장소에는 실행 소스·테스트·환경 변수 예제와 검토용 문서를 제공합니다. 실제 자격 증명, 환경 파일, DB 데이터와 업로드 파일은 포함하지 않습니다.
