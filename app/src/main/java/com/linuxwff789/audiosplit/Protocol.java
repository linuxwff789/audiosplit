package com.linuxwff789.audiosplit;

/** Wire protocol between the UI app and the system_server hook. */
public final class Protocol {
    public static final String ACTION_CONFIG = "com.linuxwff789.audiosplit.action.CONFIG";
    public static final String ACTION_STATUS = "com.linuxwff789.audiosplit.action.STATUS";
    public static final String EXTRA_JSON = "json";
    public static final String EXTRA_TEXT = "text";
    public static final String PKG = "com.linuxwff789.audiosplit";

    private Protocol() {
    }
}
