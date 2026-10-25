package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DeclaredType {

    private static final Pattern COLUMN_TYPENAME = Pattern.compile("([^\\(]*)");
    private static final Pattern COLUMN_TYPECAST =
            Pattern.compile("cast\\(.*?\\s+as\\s+(.*?)\\s*\\)");
    private static final Pattern COLUMN_PRECISION = Pattern.compile(".*?\\((.*?)\\)");

    private final String declType;

    private DeclaredType(String declType) {
        this.declType = declType;
    }

    static DeclaredType of(Col col) {
        String declType = col.decltype();
        if (declType == null) {
            String name = col.name();
            if (name != null) {
                Matcher matcher = COLUMN_TYPECAST.matcher(name);
                declType = matcher.find() ? matcher.group(1) : null;
            }
        }
        return new DeclaredType(declType);
    }

    boolean isDeclared() {
        return declType != null;
    }

    String declaredTypeName() {
        if (declType == null) {
            return null;
        }
        Matcher matcher = COLUMN_TYPENAME.matcher(declType);
        matcher.find();
        return matcher.group(1).toUpperCase(Locale.ENGLISH);
    }

    String typeName() {
        String name = declaredTypeName();
        return name != null ? name : "NUMERIC";
    }

    int precision() {
        if (declType == null) {
            return 0;
        }
        Matcher matcher = COLUMN_PRECISION.matcher(declType);
        return matcher.find() ? Integer.parseInt(matcher.group(1).split(",")[0].trim()) : 0;
    }

    int scale() {
        if (declType == null) {
            return 0;
        }
        Matcher matcher = COLUMN_PRECISION.matcher(declType);
        if (matcher.find()) {
            String[] parts = matcher.group(1).split(",");
            if (parts.length == 2) {
                return Integer.parseInt(parts[1].trim());
            }
        }
        return 0;
    }
}
