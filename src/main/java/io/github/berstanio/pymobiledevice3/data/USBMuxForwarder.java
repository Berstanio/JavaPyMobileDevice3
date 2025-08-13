package io.github.berstanio.pymobiledevice3.data;

public class USBMuxForwarder {

    private final DeviceInfo deviceInfo;
    private final int remotePort;
    private final int localPort;
    private final String localHost;

    public USBMuxForwarder(DeviceInfo deviceInfo, int remotePort, int localPort, String localHost) {
        this.deviceInfo = deviceInfo;
        this.remotePort = remotePort;
        this.localPort = localPort;
        this.localHost = localHost;
    }

    public int getLocalPort() {
        return localPort;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public String getLocalHost() {
        return localHost;
    }
}
