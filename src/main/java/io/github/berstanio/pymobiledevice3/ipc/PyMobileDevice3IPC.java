package io.github.berstanio.pymobiledevice3.ipc;

import io.github.berstanio.pymobiledevice3.data.PyMobileDevice3Error;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class PyMobileDevice3IPC implements Closeable {

    private static final boolean DEBUG = System.getProperty("java.pymobiledevice3.debug") != null;

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
    private final ConcurrentHashMap<String, CompletableFuture<?>> futures = new ConcurrentHashMap<>();

    private boolean destroyed = false;

    public PyMobileDevice3IPC() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("localhost"));
        int port = serverSocket.getLocalPort();
        ProcessBuilder pb = new ProcessBuilder()
                .command("python3" , "-u", "/Volumes/ExternalSSD/IdeaProjects/JavaPyMobileDevice3/src/main/resources/handler.py", String.valueOf(port));

        if (DEBUG)
            pb.inheritIO();
        else
            pb.redirectErrorStream(true);

        process = pb.start();

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
                    if (line == null) {
                        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        for (CompletableFuture<?> future : futures.values()) {
                            future.completeExceptionally(new PyMobileDevice3Error("Python process died: " + log));
                        }

                        close();
                        return;
                    }
                    JSONObject jsonObject = new JSONObject(line);
                    if (jsonObject.get("state").equals("failed")) {
                        String request = jsonObject.getString("request");
                        CompletableFuture<?> future = futures.remove(request);
                        future.completeExceptionally(new PyMobileDevice3Error(jsonObject.getString("error")));
                        continue;
                    }
                    int id = jsonObject.getInt("id");
                    Consumer<JSONObject> handler = commandResults.get(id);
                    if (handler == null) {
                        System.out.println("Unable to handle: " + line);
                        continue;
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

    private <U> CompletableFuture<U> createRequest(JSONObject request, BiConsumer<CompletableFuture<U>, JSONObject> handler) {
        if (destroyed)
            return CompletableFuture.failedFuture(new PyMobileDevice3Error("Python process has been destroyed"));
        int id = commandId.getAndIncrement();
        if (request.has("id"))
            throw new IllegalArgumentException("Request must not contain id field");
        request.put("id", id);

        String message = request.toString();
        if (!writeQueue.offer(message))
            return CompletableFuture.failedFuture(new RuntimeException("Write queue overflow"));

        CompletableFuture<U> future = new CompletableFuture<>();

        commandResults.put(id, jsonObject -> {
            handler.accept(future, jsonObject);
            if (future.isDone()) {
                commandResults.remove(id);
                futures.remove(message);
            }
        });

        futures.put(message, future);

        return future;
    }


    public CompletableFuture<DeviceInfo[]> listDevices() {
        JSONObject object = new JSONObject();
        object.put("command", "list_devices");

        return createRequest(object, (future, jsonObject) -> {
            JSONArray jsonArray = jsonObject.getJSONArray("result");
            DeviceInfo[] deviceInfos = new DeviceInfo[jsonArray.length()];
            for (int i = 0; i < deviceInfos.length; i++) {
                JSONObject deviceInfo = jsonArray.getJSONObject(i);

                deviceInfos[i] = DeviceInfo.fromJson(deviceInfo);
            }

            future.complete(deviceInfos);
        });
    }

    /**
     * @param uuid The uuid of the requested device - or null for the first available device
     * @return A future that will provide the result - null if the device is not connected
     */
    public CompletableFuture<DeviceInfo> getDevice(String uuid) {
        JSONObject object = new JSONObject();
        object.put("command", "get_device");
        if (uuid != null)
            object.put("device_id", uuid);

        return createRequest(object, (future, jsonObject) -> {
            if (jsonObject.get("state").equals("failed_expected")) {
                future.complete(null);
            } else {
                DeviceInfo deviceInfo = DeviceInfo.fromJson(jsonObject.getJSONObject("result"));
                future.complete(deviceInfo);
            }
        });
    }

    /**
     * @param deviceInfo The device to install to. null means first device found
     * @param path .app bundle path
     * @param installMode The installation mode. INSTALL/UPGRADE
     * @param progressCallback A callback that will be run during installation. No thread guarantees made. May be null
     */
    public CompletableFuture<String> installApp(DeviceInfo deviceInfo, String path, InstallMode installMode, IntConsumer progressCallback) {
        JSONObject object = new JSONObject();
        object.put("command", "install_app");
        object.put("mode", installMode.name());
        object.put("path", path);
        if (deviceInfo != null)
            object.put("device_id", deviceInfo.getUniqueDeviceId());

        return createRequest(object, (future, jsonObject) -> {
            if (jsonObject.has("progress")) {
                if (progressCallback != null)
                    progressCallback.accept(jsonObject.getInt("progress"));
            } else {
                String bundlePath = jsonObject.getString("result");
                future.complete(bundlePath);
            }
        });
    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        try (PyMobileDevice3IPC ipc = new PyMobileDevice3IPC()) {
            CompletableFuture<String> future = ipc.installApp(null, "/Volumes/ExternalSSD/IdeaProjects/MOE-Upstream/moe/samples-java/Calculator/ios/build/moe/xcodebuild/Release-iphoneos/ios.app", InstallMode.UPGRADE, progress -> System.out.println("Progress: " + progress + "%"));

            System.out.println("Installed to: " +  future.get());
        }
    }

    @Override
    public void close() {
        try {
            writeThread.interrupt();
            readThread.interrupt();
            socket.close();
            serverSocket.close();
            process.destroyForcibly();
            futures.clear();
            commandResults.clear();
            writeQueue.clear();

            destroyed = true;
        } catch (IOException ignored) {}
    }
}