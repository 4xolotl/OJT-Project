package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Aligns database equality with the canonical email identity used by the application. */
public final class V4__Canonical_email_identity extends BaseJavaMigration {

    private static final String LOCAL_ASCII_SPECIALS = "!#$%&'*+-/=?^_`{|}~.";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<CanonicalEmail> emails = readAndValidate(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE users DROP COLUMN IF EXISTS __email_identity_v4");
            statement.execute("ALTER TABLE users ADD COLUMN __email_identity_v4 VARCHAR(254) "
                    + "CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL");
        }
        updateEmails(connection, emails);
        swapEmailColumn(connection);
    }

    private static List<CanonicalEmail> readAndValidate(Connection connection) throws SQLException {
        List<CanonicalEmail> emails = new ArrayList<>();
        Map<String, Long> owners = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, email, password IS NOT NULL AS has_password FROM users ORDER BY id");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong("id");
                String canonical = canonicalizeV4(rows.getString("email"));
                int maxLength = rows.getBoolean("has_password") ? 100 : 254;
                if (!isSafeCanonicalEmail(canonical, maxLength)) {
                    throw new SQLException("Cannot canonicalize email for user id " + id);
                }
                Long existing = owners.putIfAbsent(canonical, id);
                if (existing != null) {
                    throw new SQLException("Cannot canonicalize duplicate user emails for ids "
                            + existing + " and " + id);
                }
                emails.add(new CanonicalEmail(id, canonical));
            }
        }
        return emails;
    }

    // Keep the V4 transformation immutable. A future identity rule requires a new migration.
    static String canonicalizeV4(String email) {
        if (!email.chars().allMatch(character -> character < 128)) {
            return email;
        }
        String stripped = email.strip();
        return stripped.toLowerCase(Locale.ROOT);
    }

    static boolean isSafeCanonicalEmail(String email) {
        return isSafeCanonicalEmail(email, 254);
    }

    static boolean isSafeCanonicalEmail(String email, int maxLength) {
        if (email == null || !email.equals(canonicalizeV4(email))) {
            return false;
        }
        int separator = email.indexOf('@');
        if (email.isBlank() || email.length() > maxLength || separator <= 0
                || separator != email.lastIndexOf('@')
                || separator >= email.length() - 1) {
            return false;
        }
        return validLocalPartV4(email.substring(0, separator))
                && validAsciiDomainV4(email.substring(separator + 1));
    }

    private static boolean validLocalPartV4(String local) {
        if (local.getBytes(StandardCharsets.UTF_8).length > 64
                || local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return false;
        }
        return local.chars().allMatch(character -> character < 128
                && (Character.isLetterOrDigit(character)
                || LOCAL_ASCII_SPECIALS.indexOf(character) >= 0));
    }

    private static boolean validAsciiDomainV4(String domain) {
        if (domain.isEmpty() || domain.length() > 253 || domain.startsWith(".") || domain.endsWith(".")) {
            return false;
        }
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            if (!label.chars().allMatch(character -> character < 128
                    && (Character.isLetterOrDigit(character) || character == '-'))) {
                return false;
            }
        }
        return true;
    }

    private static void updateEmails(Connection connection, List<CanonicalEmail> emails) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE users SET __email_identity_v4 = ? WHERE id = ?")) {
            for (CanonicalEmail email : emails) {
                update.setString(1, email.value());
                update.setLong(2, email.userId());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void swapEmailColumn(Connection connection) throws SQLException {
        List<String> uniqueIndexes = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' "
                        + "AND COLUMN_NAME = 'email' AND NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY' "
                        + "ORDER BY INDEX_NAME");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                uniqueIndexes.add(rows.getString("INDEX_NAME"));
            }
        }

        StringBuilder alter = new StringBuilder("ALTER TABLE users ");
        for (String index : uniqueIndexes) {
            alter.append("DROP INDEX ").append(quoteIdentifier(index)).append(", ");
        }
        alter.append("DROP COLUMN email, ")
                .append("CHANGE COLUMN __email_identity_v4 email VARCHAR(254) ")
                .append("CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, ")
                .append("ADD CONSTRAINT uk_users_email_v4 UNIQUE (email)");
        try (Statement statement = connection.createStatement()) {
            statement.execute(alter.toString());
        }
    }

    static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private record CanonicalEmail(long userId, String value) {
    }
}
