package org.lsposed.lspatch.share;

public class LSPConfig {

    public static final LSPConfig instance;

    public int API_CODE;
    public int VERSION_CODE;
    public String VERSION_NAME;
    public int CORE_VERSION_CODE;
    public String CORE_VERSION_NAME;
    public String CORE_VERSION_HASH;

    private LSPConfig() {
    }

    static {
        instance = new LSPConfig();
        instance.API_CODE = 102;
        instance.VERSION_CODE = 436;
        instance.VERSION_NAME = "0.7.1";
        instance.CORE_VERSION_CODE = 1;
        instance.CORE_VERSION_NAME = "1.0";
        instance.CORE_VERSION_HASH = "";
    }
}
