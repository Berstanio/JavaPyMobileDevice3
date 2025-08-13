package io.github.berstanio.pymobiledevice3.venv;

import java.io.File;

public class PyInstallation {
    private final File venv;
    private final File pythonExecutable;
    private final File handler;

    public PyInstallation(File venv, File pythonExecutable, File handler) {
        this.venv = venv;
        this.pythonExecutable = pythonExecutable;
        this.handler = handler;
    }

    public File getHandler() {
        return handler;
    }

    public File getPythonExecutable() {
        return pythonExecutable;
    }

    public File getVEnv() {
        return venv;
    }
}
