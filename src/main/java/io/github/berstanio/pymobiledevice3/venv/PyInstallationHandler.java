package io.github.berstanio.pymobiledevice3.venv;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class PyInstallationHandler {

    private static final boolean DEBUG = System.getProperty("java.pymobiledevice3.debug") != null;

    private static final String VENV_NAME = ".venv";
    private static final String HANDLER_NAME = "handler.py";
    private static final String REQUIREMENTS_NAME = "requirements.txt";
    private static final String PYTHON_PATH = "bin/python3";

    public static PyInstallation install(File directory) {
        directory.mkdirs();
        if (!directory.exists() || !directory.isDirectory())
            throw new IllegalArgumentException(directory.getAbsolutePath() + " does not exist or is not a directory");
        File venv = new File(directory, VENV_NAME);
        if (venv.exists()) {
            if (checkVEnv(venv)) {
                // Env is valid. Copy files again, just in case
                copyFiles(directory);
                installRequirements(directory);
                return new PyInstallation(venv, new File(venv, PYTHON_PATH), new File(directory, HANDLER_NAME));
            }
            if (!venv.delete())
                throw new IllegalStateException(venv.getAbsolutePath() + " exists, is invalid and cannot be deleted");
        }

        // We have no env. Create it
        try {
            int exitCode = new ProcessBuilder("python3", "--version").start().waitFor();
            if (exitCode != 0)
                throw new IllegalStateException("Python3 is not installed or on the path");
        } catch (InterruptedException | IOException e) {
            throw new IllegalStateException("Python3 is not installed or on the path", e);
        }

        ProcessBuilder pb = new ProcessBuilder("python3", "-m", "venv", venv.getAbsolutePath());

        if (DEBUG) {
            pb.inheritIO();
        } else {
            pb.redirectErrorStream(true);
        }

        try {
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("Failed to install venv: " + stdout);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to install venv " + venv.getAbsolutePath(), e);
        }

        if (!checkVEnv(venv)) {
            throw new IllegalStateException("Installed venv at " + venv.getAbsolutePath() + ", but it's invalid?");
        }

        installRequirements(directory);

        return new PyInstallation(venv,  new File(venv, PYTHON_PATH), new File(directory, HANDLER_NAME));
    }

    private static void copyFiles(File directory) {
        try {
            try (InputStream in = PyInstallationHandler.class.getResourceAsStream("/" + HANDLER_NAME)) {
                if (in == null)
                    throw new IllegalStateException(HANDLER_NAME + " not found in resources");
                Files.copy(in, directory.toPath().resolve(HANDLER_NAME), StandardCopyOption.REPLACE_EXISTING);
            }

            try (InputStream in = PyInstallationHandler.class.getResourceAsStream("/" + REQUIREMENTS_NAME)) {
                if (in == null)
                    throw new IllegalStateException(REQUIREMENTS_NAME + " not found in resources");
                Files.copy(in, directory.toPath().resolve(REQUIREMENTS_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean checkVEnv(File venv) {
        File pythonExecutable = new File(venv, PYTHON_PATH);
        if (!pythonExecutable.exists()) {
            return false;
        }

        try {
            int exitCode = new ProcessBuilder(pythonExecutable.getAbsolutePath(), "--version").start().waitFor();
            if (exitCode != 0)
                return false;
        } catch (InterruptedException | IOException e) {
            return false;
        }

        return true;
    }

    private static void installRequirements(File directory) {
        File venv = new File(directory, VENV_NAME);
        File requirements = new File(directory, REQUIREMENTS_NAME);
        File pythonExecutable = new File(venv, PYTHON_PATH);
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonExecutable.getAbsolutePath(), "-m", "pip", "install", "-r", requirements.getAbsolutePath());
            if (DEBUG) {
                pb.inheritIO();
            } else {
                pb.redirectErrorStream(true);
            }
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0)
                throw new IllegalStateException("Failed to install dependencies " + requirements.getAbsolutePath() + ": " + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (InterruptedException | IOException e) {
            throw new IllegalStateException("Failed to install dependencies " + requirements.getAbsolutePath(), e);
        }
    }
}
