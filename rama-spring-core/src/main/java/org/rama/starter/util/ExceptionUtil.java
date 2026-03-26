package org.rama.starter.util;

public final class ExceptionUtil {
    private ExceptionUtil() {
    }

    public static String getDeepestExceptionMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
