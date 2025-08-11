package io.github.berstanio.pymobiledevice3.data;

public interface DeviceListener {

    void deviceAdded(String udid);
    void deviceRemoved(String udid);
}
