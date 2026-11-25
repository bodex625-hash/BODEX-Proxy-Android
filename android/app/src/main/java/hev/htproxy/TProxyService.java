package hev.htproxy;

/**
 * JNI bridge matching the upstream HevSocks5Tunnel Android interface.
 *
 * The native library must be built for each Android ABI and packaged as
 * libhev-socks5-tunnel.so before these methods can be called.
 */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {
    }

    public static native boolean TProxyStartService(String configPath, int tunFileDescriptor);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
