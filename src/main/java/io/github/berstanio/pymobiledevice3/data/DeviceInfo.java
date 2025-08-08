package io.github.berstanio.pymobiledevice3.data;

public class DeviceInfo {
    private final String identifier;
    private final String deviceClass;
    private final String deviceName;
    private final String buildVersion;
    private final String productVersion;
    private final String productType;
    private final String uniqueDeviceId;
    private final ConnectionType connectionType;

    public DeviceInfo(String identifier, String deviceClass, String deviceName, String buildVersion, String productVersion, String productType, String uniqueDeviceId, ConnectionType connectionType) {
        this.identifier = identifier;
        this.deviceClass = deviceClass;
        this.deviceName = deviceName;
        this.buildVersion = buildVersion;
        this.productVersion = productVersion;
        this.productType = productType;
        this.uniqueDeviceId = uniqueDeviceId;
        this.connectionType = connectionType;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getDeviceClass() {
        return deviceClass;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getBuildVersion() {
        return buildVersion;
    }

    public String getProductVersion() {
        return productVersion;
    }

    public String getProductType() {
        return productType;
    }

    public String getUniqueDeviceId() {
        return uniqueDeviceId;
    }

    public ConnectionType getConnectionType() {
        return connectionType;
    }

    @Override
    public String toString() {
        return "DeviceInfo{" + "identifier='" + identifier + '\'' + ", deviceClass='" + deviceClass + '\''
                + ", deviceName='" + deviceName + '\'' + ", buildVersion='" + buildVersion + '\'' + ", productVersion='"
                + productVersion + '\'' + ", productType='" + productType + '\'' + ", uniqueDeviceId='" + uniqueDeviceId
                + '\'' + ", connectionType=" + connectionType + '}';
    }
}
