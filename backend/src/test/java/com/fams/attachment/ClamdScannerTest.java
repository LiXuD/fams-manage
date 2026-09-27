package com.fams.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class ClamdScannerTest {
    @Test
    void instreamFramingTransmitsOriginalBytesAndFailsClosedOnErrors() throws Exception {
        byte[] payload = "scan these exact bytes".getBytes(StandardCharsets.UTF_8);
        for (String reply : java.util.List.of("stream: OK", "stream: Test-Signature FOUND",
                "INSTREAM size limit exceeded ERROR", "unexpected")) {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                var peer = CompletableFuture.runAsync(() -> {
                    try (var socket = server.accept()) {
                        DataInputStream input = new DataInputStream(socket.getInputStream());
                        assertArrayEquals("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII), input.readNBytes(10));
                        ByteArrayOutputStream received = new ByteArrayOutputStream();
                        int length;
                        while ((length = input.readInt()) != 0) { received.write(input.readNBytes(length)); }
                        assertArrayEquals(payload, received.toByteArray());
                        socket.getOutputStream().write((reply + "\0").getBytes(StandardCharsets.UTF_8));
                    } catch (Exception error) { throw new IllegalStateException(error); }
                });
                var scanner = new ClamdScanner(server.getInetAddress().getHostAddress(), server.getLocalPort(), 2000);
                MalwareScanner.Result expected = reply.equals("stream: OK") ? MalwareScanner.Result.CLEAN
                        : reply.endsWith(" FOUND") ? MalwareScanner.Result.INFECTED : MalwareScanner.Result.UNAVAILABLE;
                assertEquals(expected, scanner.scan(new ByteArrayInputStream(payload)));
                peer.get();
            }
        }
    }

    @Test
    void unresponsiveDaemonHasBoundedDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var scanner = new ClamdScanner(server.getInetAddress().getHostAddress(), server.getLocalPort(), 100);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertEquals(MalwareScanner.Result.UNAVAILABLE, scanner.scan(new ByteArrayInputStream(new byte[]{1}))));
        }
    }
}
