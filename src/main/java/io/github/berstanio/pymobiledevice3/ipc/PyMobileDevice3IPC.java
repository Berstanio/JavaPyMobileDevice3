package io.github.berstanio.pymobiledevice3.ipc;

import io.github.berstanio.pymobiledevice3.data.ConnectionType;
import io.github.berstanio.pymobiledevice3.data.DeviceInfo;
import io.github.berstanio.pymobiledevice3.data.InstallMode;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class PyMobileDevice3IPC implements Closeable {

    private final Process process;
    private final ServerSocket serverSocket;
    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;

    private final AtomicInteger commandId = new AtomicInteger(0);
    private final Thread writeThread;
    private final Thread readThread;
    private final ArrayBlockingQueue<String> writeQueue = new ArrayBlockingQueue<>(16);
    private final ConcurrentHashMap<Integer, Consumer<JSONObject>> commandResults = new ConcurrentHashMap<>();

    public PyMobileDevice3IPC() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("localhost"));
        int port = serverSocket.getLocalPort();
        process = new ProcessBuilder()
                .command("python3" , "-u", "/Volumes/ExternalSSD/IdeaProjects/JavaPyMobileDevice3/src/main/resources/handler.py", String.valueOf(port))
                .inheritIO()
                .start();

        socket = serverSocket.accept();
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(socket.getOutputStream(), true);

        writeThread = new Thread(() -> {
            while (true) {
                try {
                    String toWrite = writeQueue.take();
                    writer.println(toWrite);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "PyMobileDevice3IPC-WriteDispatcher");
        writeThread.setDaemon(true);
        writeThread.start();

        readThread = new Thread(() -> {
            while (true) {
                try {
                    String line = reader.readLine();
                    JSONObject jsonObject = new JSONObject(line);
                    int id = jsonObject.getInt("id");
                    Consumer<JSONObject> handler = commandResults.get(id);
                    if (handler == null) {
                        System.out.println("Unable to handle: " + line);
                        return;
                    }

                    handler.accept(jsonObject);
                } catch (IOException e) {
                    break;
                }
            }
        }, "PyMobileDevice3IPC-ReadDispatcher");
        readThread.setDaemon(true);
        readThread.start();
    }


    public CompletableFuture<DeviceInfo[]> listDevices() {
        int id = commandId.getAndIncrement();
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("command", "list_devices");

        if (!writeQueue.offer(object.toString()))
            return CompletableFuture.completedFuture(null);

        CompletableFuture<DeviceInfo[]> future = new CompletableFuture<>();

        commandResults.put(id, jsonObject -> {
            JSONArray jsonArray = jsonObject.getJSONArray("result");
            DeviceInfo[] deviceInfos = new DeviceInfo[jsonArray.length()];
            for (int i = 0; i < deviceInfos.length; i++) {
                JSONObject deviceInfo = jsonArray.getJSONObject(i);

                deviceInfos[i] = new DeviceInfo(deviceInfo.getString("Identifier"), deviceInfo.getString("DeviceClass"),
                        deviceInfo.getString("DeviceName"), deviceInfo.getString("BuildVersion"),
                        deviceInfo.getString("ProductVersion"), deviceInfo.getString("ProductType"),
                        deviceInfo.getString("UniqueDeviceID"), ConnectionType.valueOf(deviceInfo.getString("ConnectionType")));
            }

            commandResults.remove(id);

            future.complete(deviceInfos);
        });

        return future;
    }

    /**
     * @param deviceInfo The device to install to. null means first device found
     * @param path .app bundle path
     * @param installMode The installation mode. INSTALL/UPGRADE
     * @param progressCallback A callback that will be run during installation. No thread guarantees made
     */
    public CompletableFuture<String> installApp(DeviceInfo deviceInfo, String path, InstallMode installMode, IntConsumer progressCallback) {
        int id = commandId.getAndIncrement();
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("command", "install_app");
        object.put("mode", installMode.name());
        object.put("path", path);
        if (deviceInfo != null)
            object.put("device_id", deviceInfo.getUniqueDeviceId());

        if (!writeQueue.offer(object.toString()))
            return CompletableFuture.completedFuture(null);

        CompletableFuture<String> future = new CompletableFuture<>();
        commandResults.put(id, jsonObject -> {
            if (jsonObject.has("progress")) {
                if (progressCallback != null)
                    progressCallback.accept(jsonObject.getInt("progress"));
            } else {
                String bundlePath = jsonObject.getString("result");
                commandResults.remove(id);

                future.complete(bundlePath);
            }
        });

        return future;
    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        try (PyMobileDevice3IPC ipc = new PyMobileDevice3IPC()) {
            CompletableFuture<DeviceInfo[]> result = ipc.listDevices();

            DeviceInfo[] infos = result.get();
            for (DeviceInfo deviceInfo : infos) {
                System.out.println(deviceInfo);
            }
        }
    }

    @Override
    public void close() throws IOException {
        writeThread.interrupt();
        readThread.interrupt();
        socket.close();
        serverSocket.close();
        process.destroyForcibly();
    }
}