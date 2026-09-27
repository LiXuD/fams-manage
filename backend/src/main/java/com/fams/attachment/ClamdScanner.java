package com.fams.attachment;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** INSTREAM sends bytes to a configured private clamd endpoint, never a user-supplied server path. */
public final class ClamdScanner implements MalwareScanner {
    private final InetSocketAddress endpoint;
    private final int timeoutMillis;

    public ClamdScanner(String host, int port, int timeoutMillis) {
        if (timeoutMillis <= 0 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("Scan timeout must be within 1..60000 milliseconds"); }
        endpoint = new InetSocketAddress(host, port);
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public Result scan(InputStream content) {
        var watchdog = Executors.newSingleThreadScheduledExecutor();
        try (Socket socket = new Socket()) {
            watchdog.schedule(() -> {
                try { socket.close(); } catch (IOException ignored) { /* Already closed. */ }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            socket.connect(endpoint, timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = content.read(buffer)) != -1) {
                total += read;
                if (total > LocalAttachmentStorage.MAX_FILE_BYTES) { return Result.UNAVAILABLE; }
                output.writeInt(read);
                output.write(buffer, 0, read);
            }
            output.writeInt(0);
            output.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            int next;
            while ((next = input.read()) != 0) {
                if (next < 0 || response.size() >= 4096) { return Result.UNAVAILABLE; }
                response.write(next);
            }
            String result = response.toString(StandardCharsets.UTF_8);
            if ("stream: OK".equals(result)) { return Result.CLEAN; }
            if (result.startsWith("stream: ") && result.endsWith(" FOUND")) { return Result.INFECTED; }
            return Result.UNAVAILABLE;
        } catch (IOException error) { return Result.UNAVAILABLE; }
        finally { watchdog.shutdownNow(); }
    }
}
