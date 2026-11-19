package org.apache.shiro.biz.cache.j2cache;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Value;
import net.oschina.j2cache.util.SerializationUtils;
import net.rubyeye.xmemcached.GetsResponse;
import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.exception.MemcachedException;
import net.rubyeye.xmemcached.transcoders.CachedData;
import net.rubyeye.xmemcached.transcoders.SerializingTranscoder;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.cache.CacheException;

/** The original J2Cache retry count and Memcached deadline. */
@Value
class MemcachedLegacyWindow {

    private static final int MAX_COUNTER_BYTES = 65_536;
    private static final int MAX_CLOCK_ATTEMPTS = 5;
    private static final SerializingTranscoder TRANSCODER = new SerializingTranscoder();

    int count;
    int deadline;

    static MemcachedLegacyWindow read(InetSocketAddress source, String key, int timeoutMillis) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(source, timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            return read(socket, key);
        }
    }

    static MemcachedLegacyWindow read(Socket socket, String key) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length == 0 || keyBytes.length > 250) {
            throw new IllegalArgumentException("Invalid legacy Memcached key length");
        }
        String request = "mg " + Base64.getEncoder().encodeToString(keyBytes) + " b t f v u";
        DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        OutputStream output = socket.getOutputStream();
        for (int attempt = 0; attempt < MAX_CLOCK_ATTEMPTS; attempt++) {
            long before = time(input, output);
            command(output, request);
            String response = line(input);
            if ("EN".equals(response)) {
                return null;
            }
            String[] fields = StringUtils.split(response);
            if (Objects.isNull(fields) || fields.length < 4 || !"VA".equals(fields[0])) {
                throw new CacheException("Memcached source does not support meta value and expiry reads");
            }
            int bytes = Integer.parseInt(fields[1]);
            if (bytes <= 0 || bytes > MAX_COUNTER_BYTES) {
                throw new CacheException("Invalid legacy Memcached counter size");
            }
            int ttl = Integer.parseInt(flag(fields, 't'));
            int flags = Integer.parseUnsignedInt(flag(fields, 'f'));
            byte[] payload = new byte[bytes];
            input.readFully(payload);
            if (StringUtils.isNotEmpty(line(input))) {
                throw new CacheException("Invalid legacy Memcached value terminator");
            }
            if (ttl == 0) {
                return null;
            }
            if (ttl == -1) {
                return decode(payload, flags, 0);
            }
            if (ttl < -1) {
                throw new CacheException("Invalid legacy Memcached expiry");
            }
            // Both samples must belong to the server second in which it computed the remaining TTL.
            if (before == time(input, output)) {
                return decode(payload, flags, Math.toIntExact(before + ttl));
            }
        }
        throw new CacheException("Could not determine the legacy Memcached deadline at server precision");
    }

    private static MemcachedLegacyWindow decode(byte[] payload, int flags, int deadline) throws IOException {
        Object raw = TRANSCODER.decode(new CachedData(flags, payload));
        return new MemcachedLegacyWindow(counterValue(raw), deadline);
    }

    static MemcachedLegacyWindow readMatchingVersion(MemcachedClient source, String key, long sourceCas,
                                                     MemcachedLegacyWindow expected)
            throws TimeoutException, InterruptedException, MemcachedException, IOException {
        GetsResponse<Object> current = source.gets(key, TRANSCODER);
        if (Objects.isNull(current) || current.getCas() != sourceCas) {
            return null;
        }
        return counterValue(current.getValue()) == expected.getCount() ? expected : null;
    }

    private static int counterValue(Object raw) throws IOException {
        if (!(raw instanceof byte[])) {
            throw new CacheException("Legacy Memcached value must contain J2Cache bytes");
        }
        Object counter = SerializationUtils.deserialize((byte[]) raw);
        if (!(counter instanceof AtomicInteger) || ((AtomicInteger) counter).get() < 0) {
            throw new CacheException("Legacy Memcached value must contain a nonnegative retry counter");
        }
        return ((AtomicInteger) counter).get();
    }

    private static long time(DataInputStream input, OutputStream output) throws IOException {
        command(output, "stats");
        long time = -1;
        for (int index = 0; index < 256; index++) {
            String response = line(input);
            if ("END".equals(response) && time >= 0) {
                return time;
            }
            if (!response.startsWith("STAT ")) {
                break;
            }
            if (response.startsWith("STAT time ")) {
                time = Long.parseLong(response.substring("STAT time ".length()));
            }
        }
        throw new CacheException("Memcached source did not report its server clock");
    }

    private static String flag(String[] fields, char name) {
        for (int index = 2; index < fields.length; index++) {
            if (fields[index].charAt(0) == name && fields[index].length() > 1) {
                return fields[index].substring(1);
            }
        }
        throw new CacheException("Memcached source omitted a required meta flag");
    }

    private static void command(OutputStream output, String request) throws IOException {
        output.write((request + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static String line(DataInputStream input) throws IOException {
        StringBuilder result = new StringBuilder();
        while (result.length() < 512) {
            int next = input.readUnsignedByte();
            if (next == '\r' && input.readUnsignedByte() == '\n') {
                return result.toString();
            }
            if (next < 32 || next >= 127) {
                break;
            }
            result.append((char) next);
        }
        throw new CacheException("Invalid Memcached protocol response");
    }
}
