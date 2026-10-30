package com.finsights.portfolio.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Short-lived, in-memory holding pen for a file the user attaches in the Goku chat, between the
 * upload call and the {@code import_statement} tool call that reads it — the tool-calling loop's
 * JSON-only input has nowhere to carry raw bytes, so the chat conversation only ever sees an
 * opaque {@code uploadId}, never the file itself.
 *
 * <p>Single backend instance only (no Redis/DB) — a restart losing an in-flight upload just means
 * "attach it again," which is an acceptable trade for not persisting someone's statement to disk.
 * {@link #take} both scopes to the user who uploaded it and consumes it, so the same id can't be
 * replayed and a guess at someone else's id gets the same "not found" either way.
 */
@Service
public class GokuUploadStore {
    private static final Duration TTL = Duration.ofMinutes(20);
    private static final int MAX_BYTES = 15 * 1024 * 1024;

    private record Upload(String userId, byte[] bytes, String filename, Instant expiresAt) {}

    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    public String store(String userId, String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was uploaded");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That file is larger than " + (MAX_BYTES / (1024 * 1024)) + "MB.");
        }
        purgeExpired();
        String id = UUID.randomUUID().toString();
        uploads.put(id, new Upload(userId, bytes, filename, Instant.now().plus(TTL)));
        return id;
    }

    public byte[] take(String userId, String uploadId) {
        // Peek rather than remove up front — a wrong-user attempt (an unguessable id, in
        // practice, but still) must 404 without destroying the real owner's pending upload.
        Upload upload = uploadId == null ? null : uploads.get(uploadId);
        if (upload == null || upload.expiresAt().isBefore(Instant.now()) || !upload.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That upload has expired — attach the file again.");
        }
        uploads.remove(uploadId);
        return upload.bytes();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        uploads.values().removeIf(u -> u.expiresAt().isBefore(now));
    }
}
