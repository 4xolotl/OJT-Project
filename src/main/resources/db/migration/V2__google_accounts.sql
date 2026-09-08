-- Widen email and remove only the password NOT NULL constraint. Existing rows and indexes remain intact.
-- Keep the character set/collation used by the project's MariaDB 11.4 board schema.
ALTER TABLE users
    MODIFY COLUMN email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci NOT NULL,
    MODIFY COLUMN password VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci NULL;

CREATE TABLE oauth_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(32) NOT NULL,
    -- OIDC subjects are opaque and case-sensitive, unlike the table's normal text comparison.
    subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_oauth_account_provider_subject UNIQUE (provider, subject),
    INDEX idx_oauth_accounts_user (user_id),
    CONSTRAINT fk_oauth_accounts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;
