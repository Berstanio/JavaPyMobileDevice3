package io.github.berstanio.pymobiledevice3.data;

public class PyMobileDevice3Error extends Exception {


    public PyMobileDevice3Error(String error) {
        super(error);
    }

    public PyMobileDevice3Error(String error, String pyBacktrace) {
        super(error);
        setStackTrace(new StackTraceElement[] {
                new StackTraceElement("\n" + pyBacktrace, "", "", -1)
        });
    }
}
