package io.github.berstanio.pymobiledevice3.data;

public enum ConnectionType {
    USB,
    NETWORK;

    public static ConnectionType fromString(String value) {
        switch (value) {
            case "USB":
                return USB;
            case "Network":
                return NETWORK;
            default:
                return null;
        }
    }
}
