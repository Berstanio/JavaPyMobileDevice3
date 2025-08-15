package io.github.berstanio.pymobiledevice3.daemon;

import com.badlogic.gdx.jnigen.commons.HostDetection;
import com.badlogic.gdx.jnigen.commons.Os;
import io.github.berstanio.pymobiledevice3.ipc.PyMobileDevice3IPC;
import io.github.berstanio.pymobiledevice3.venv.PyInstallation;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

public class DaemonHandler {
    public static final String BASE_PORT_NAME = "javapymobiledevice3";
    public static final File BASE_TEMP_DIR_WIN = Paths.get(System.getProperty("user.home"), "AppData", "Local", "Temp").toFile();
    public static final File BASE_TEMP_DIR_UNIX = new File("/tmp/" + BASE_PORT_NAME + "/");
    public static final File UNIX_PORT_PATH = new File(BASE_TEMP_DIR_UNIX, BASE_PORT_NAME + "--" + getUnixUserId() + ".port");
    public static final File WINDOWS_PORT_PATH = new File(BASE_TEMP_DIR_WIN, BASE_PORT_NAME + ".port");


    public static File getTempDir() {
        if (HostDetection.os == Os.Windows)
            return BASE_TEMP_DIR_WIN;
        else
            return BASE_TEMP_DIR_UNIX;
    }

    public static File getPortFile() {
        if (HostDetection.os == Os.Windows)
            return WINDOWS_PORT_PATH;
        else
            return UNIX_PORT_PATH;
    }

    public static File getLogFile() {
        File tempDir = getTempDir();
        tempDir.mkdirs();
        return new File(tempDir, BASE_PORT_NAME + ".log");
    }

    public static int getDaemonPort() {
        if (!getPortFile().exists())
            return -1;
        try {
            return Integer.parseInt(Files.readString(getPortFile().toPath(), StandardCharsets.UTF_8));
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    public static boolean isDaemonRunning() {
        Socket socket = getDaemonSocket();
        if (socket == null)
            return false;
        try {
            socket.close();
        } catch (IOException e) {
            return false;
        }

        return true;
    }

    public static Socket getDaemonSocket() {
        int port = getDaemonPort();
        if (port == -1)
            return null;

        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            return socket;
        } catch (IOException e) {
            return null;
        }
    }

    public static void startDaemon(PyInstallation installation) throws IOException {
        File portFile = getPortFile();
        Files.deleteIfExists(portFile.toPath());
        ProcessBuilder pb = new ProcessBuilder()
                .command(installation.getPythonExecutable().getAbsolutePath() , "-u", installation.getHandler().getAbsolutePath(), String.valueOf(
                        PyMobileDevice3IPC.PROTOCOL_VERSION), portFile.getAbsolutePath());

        pb.redirectErrorStream(true);
        pb.redirectOutput(getLogFile());
        pb.start();

        for (int i = 0; i < 60; i++) {
            if (isDaemonRunning()) {
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Failed to start Daemon - timeout");
    }

    private static String getUnixUserId() {
        try {
            Process process = new ProcessBuilder("id", "-u").start();
            int res = process.waitFor();
            if (res != 0)
                throw new IllegalStateException("Failed to retrive uid");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            return reader.readLine().trim();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to retrive uid");
        }
    }
}
