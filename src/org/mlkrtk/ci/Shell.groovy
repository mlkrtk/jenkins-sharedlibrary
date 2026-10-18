package org.mlkrtk.ci

class Shell implements Serializable {

    /** Docker/OCI tag grammar: [A-Za-z0-9_][A-Za-z0-9_.-]{0,127}. */
    static final String TAG_PATTERN = /[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}/

    /** Single-quotes a value so it can be interpolated into a POSIX shell command verbatim. */
    static String quote(Object value) {
        return "'" + String.valueOf(value).replace("'", "'\"'\"'") + "'"
    }

    static boolean isValidTag(Object tag) {
        return tag != null && (tag.toString() ==~ TAG_PATTERN)
    }
}
