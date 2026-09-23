package org.example.db.util;

/** Metadata names are bound JDBC values, never SQL fragments. Preserve quoted/case-sensitive names. */
public final class IdentifierUtils {
    private IdentifierUtils() {}
    public static String requireSafeIdentifier(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + "必须是有效的对象名，且长度不能超过 256");
        }
        return value;
    }
}
