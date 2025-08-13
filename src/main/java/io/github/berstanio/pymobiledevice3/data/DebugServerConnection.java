package io.github.berstanio.pymobiledevice3.data;

public class DebugServerConnection {

    private final DeviceInfo deviceInfo;
    private final String hostname;
    private final int port;

    public DebugServerConnection(DeviceInfo info, String hostname, int port) {
        this.deviceInfo = info;
        this.hostname = hostname;
        this.port = port;
    }

    public DeviceInfo getDeviceInfo() {
        return deviceInfo;
    }

    public String getHostname() {
        return hostname;
    }

    public int getPort() {
        return port;
    }
}
