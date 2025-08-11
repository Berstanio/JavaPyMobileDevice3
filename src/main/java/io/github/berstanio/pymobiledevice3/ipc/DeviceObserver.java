package io.github.berstanio.pymobiledevice3.ipc;

import io.github.berstanio.pymobiledevice3.data.DeviceListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class DeviceObserver {

    private final List<DeviceListener> listeners = new ArrayList<>();
    private final PyMobileDevice3IPC ipc;
    private HashSet<String> devices = new HashSet<>();

    public DeviceObserver(PyMobileDevice3IPC ipc) {
        this.ipc = ipc;
        Thread thread = new Thread(() -> {
            while (true) {
                if (!ipc.isAlive())
                    return;

                synchronized (this) {
                    HashSet<String> newDevices = new HashSet<>(Arrays.asList(ipc.listDevicesUDID().join()));
                    for (String device : devices) {
                        if (!newDevices.contains(device)) {
                            for (DeviceListener listener : listeners) {
                                listener.deviceRemoved(device);
                            }
                        }
                    }

                    for (String device : newDevices) {
                        if (!devices.contains(device)) {
                            for (DeviceListener listener : listeners) {
                                listener.deviceAdded(device);
                            }
                        }
                    }

                    devices = newDevices;
                }

                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "DeviceObserver");
        thread.setDaemon(true);
        thread.start();
    }

    public void addListener(DeviceListener listener) {
        synchronized (this) {
            listeners.add(listener);
            for (String device : devices) {
                listener.deviceAdded(device);
            }
        }
    }

    public void removeListener(DeviceListener listener) {
        synchronized (this) {
            listeners.remove(listener);
        }
    }
}
