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

### 로그인 실패 제한 설정 (선택)

로그인 실패 제한은 출발지·계정 조합, 계정 전체, 출발지 전체를 각각 추적하며 다음 환경 변수로 조정합니다.

| 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `LOGIN_RATE_LIMIT_SOURCE_ACCOUNT_THRESHOLD` | `5` | 출발지·계정 조합의 실패 임계값 |
| `LOGIN_RATE_LIMIT_ACCOUNT_THRESHOLD` | `10` | 여러 출발지에서 누적한 계정 전체의 실패 임계값 |
| `LOGIN_RATE_LIMIT_SOURCE_THRESHOLD` | `20` | 출발지 전체의 실패 임계값 |
| `LOGIN_RATE_LIMIT_INITIAL_BACKOFF` | `30s` | 최초 임계값 도달 후 대기 시간 |
| `LOGIN_RATE_LIMIT_MAX_BACKOFF` | `1h` | 지수 백오프 최대 대기 시간 |
| `LOGIN_RATE_LIMIT_RECORD_TTL` | `1h` | 계정 관련 실패 기록 유지 시간 |
| `LOGIN_RATE_LIMIT_SOURCE_RECORD_TTL` | `1m` | 출발지 전체 실패 기록 유지 시간 |
| `LOGIN_RATE_LIMIT_MAX_SOURCE_ACCOUNTS` | `16384` | 출발지·계정 조합 기록 상한 |
| `LOGIN_RATE_LIMIT_MAX_ACCOUNTS` | `32768` | 우선 보관하는 정확한 계정 기록 상한 |
| `LOGIN_RATE_LIMIT_MAX_SOURCES` | `4096` | 출발지 기록 상한 |

출발지·계정 임계값을 계정 전체 임계값보다 낮게 두어 한 출발지의 반복 공격을 먼저 제한하고, 여러 출발지를 이용한 공격은 계정 전체에서 누적합니다. 이 숫자는 보안 표준이 의무화한 값이 아니라 프로젝트의 초기값입니다. 운영 환경의 로그인 실패 지표, 정상 트래픽과 인스턴스 메모리 용량을 확인해 조정합니다.

출발지는 애플리케이션 서버가 확인한 직접 연결 주소를 사용합니다. 리버스 프록시 뒤에 배포할 때는 프록시가 외부의 전달 헤더를 제거하고 새 값을 설정하도록 한 뒤, 신뢰하는 프록시에서 온 주소만 해석하도록 서버를 구성합니다. 임의의 `X-Forwarded-For` 헤더를 직접 신뢰하면 안 되며, 여러 사용자가 주소를 공유하는 NAT 환경에서는 출발지 임계값을 트래픽에 맞게 조정합니다.

계정 전체 범위는 설정한 상한까지 이메일별 정확한 기록을 교체하지 않고 보존합니다. 상한에 도달한 뒤 처음 확인하는 이메일은 프로세스마다 무작위로 생성한 비밀값을 이용해 1,024개의 공유 버킷 중 하나에 배치합니다. 따라서 임의 이메일을 계속 바꿔도 계정 기록은 `LOGIN_RATE_LIMIT_MAX_ACCOUNTS + 1024`개를 넘지 않으며, 계정 기록 포화만으로 전체 로그인을 `503` 상태로 만들지 않습니다. 공유 버킷은 서로 다른 이메일의 실패가 보수적으로 합쳐질 수 있고, 다른 이메일의 로그인 성공으로 실패 기록을 지우지 않습니다. 모든 공유 버킷 기록이 만료되고 정확한 기록 공간이 생기면 이메일별 기록 배치를 다시 시작합니다.

출발지·계정 조합과 출발지 전체에서는 제한 중이거나 처리 중인 기록과 이미 임계값에 도달한 기록을 보존하고, 임계값 미도달 유휴 기록 중 실패 횟수가 적고 오래된 기록부터 교체합니다. 안전하게 교체할 기록이 없을 때만 새 로그인 키에 `503`을 반환합니다. `security.login.rate.limit.tracked.records`, `security.login.rate.limit.account.records`, `security.login.rate.limit.account.overflow.active`, `security.login.rate.limit.decisions` 지표로 용량 거부와 공유 버킷 전환을 감시합니다. 제한 기록은 애플리케이션 인스턴스별 메모리에 저장되어 재시작하면 초기화되므로, 여러 인스턴스를 운영할 때는 Redis와 같은 원자적 공유 저장소와 엣지 계층의 요청 제한을 함께 사용합니다.

메모리 상한을 유지하기 위해 출발지 관련 임계값 미도달 기록을 교체하므로, 상한을 채울 정도의 대규모 분산 공격에서는 해당 두 범위의 실패 누적이 초기화될 수 있습니다. 공격자가 정확한 계정 기록 공간을 먼저 채우면 새 계정이 공유 버킷을 사용하는 저하 상태를 지속시킬 수도 있습니다. 계정 전체 제한은 계속 동작하지만 버킷 충돌에 따른 정상 요청 제한 가능성이 커지므로, 운영 환경에서는 공유 저장소와 엣지 제한으로 용량 압박 자체를 줄여야 합니다.

공격자가 여러 출발지를 이용해 계정 임계값에 도달한 뒤 백오프 종료 시점마다 실패를 반복하면 특정 계정의 로그인을 계속 지연할 수 있습니다. 실제 운영에서는 위험 기반 판정, CAPTCHA, MFA와 계정 복구 절차를 함께 적용해 이러한 계정 잠금형 서비스 거부 위험을 줄입니다.

설계 원칙은 [NIST SP 800-63B-4의 인증 시도 제한](https://pages.nist.gov/800-63-4/sp800-63b.html#rate-limiting-throttling), [OWASP Authentication Cheat Sheet의 로그인 제한](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#login-throttling), [OWASP Bot Management Cheat Sheet의 계정·출발지별 제한](https://cheatsheetseries.owasp.org/cheatsheets/Bot_Management_and_Anti-Automation_Cheat_Sheet.html#rate-limiting-and-quotas)을 참고합니다.

### 이메일 식별자와 기존 데이터 이전

회원가입과 일반 로그인은 ASCII 이메일 주소만 허용합니다. 입력 앞뒤의 ASCII 공백을 제거하고 대문자를 소문자로 통일하며, 국제화 도메인은 애플리케이션에서 임의 변환하지 않으므로 사전에 Punycode A-label 형식으로 입력해야 합니다. Google 신규 계정에도 같은 규칙을 적용합니다.

V4 데이터베이스 마이그레이션은 기존 이메일을 같은 규칙으로 점검한 뒤 별도 임시 열에 변환하고 마지막 단계에서 원본 열과 교체합니다. 비ASCII 주소, 길이 초과 주소 또는 변환 후 중복 주소가 있으면 사용자 ID만 포함한 오류로 중단하므로 배포 전에 해당 행을 수동으로 정리해야 합니다. 이전 과정에서 기존 인스턴스가 이메일을 쓰면 변경 유실이나 마이그레이션 실패가 발생할 수 있으므로, 모든 애플리케이션 인스턴스와 기타 쓰기 작업을 중지한 상태에서 배포합니다.

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
