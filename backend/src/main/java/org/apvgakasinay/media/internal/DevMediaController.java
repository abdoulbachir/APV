package org.apvgakasinay.media.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only serving of fixture pictures, so the frontend can be integrated before the
 * real storage service exists (a later milestone). Not present in production.
 *
 * It serves a file only when the token belongs to a published picture. The file location
 * comes from the database row, never from the request, and must stay inside the configured
 * folder, also after symbolic links are resolved. Every size variant returns the same original
 * file here; resized copies come with the real storage service.
 */
@RestController
@Profile({"dev", "test"})
class DevMediaController {

    private final JdbcClient jdbc;
    private final Path root;

    DevMediaController(JdbcClient jdbc, @Value("${apv.media.dev-root:}") String root) {
        this.jdbc = jdbc;
        this.root = root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize();
    }

    @GetMapping("/media/public/{token:[A-Za-z0-9_-]{22,64}}/{variant:[a-z0-9-]{1,32}}.{format:webp|jpg|png|avif}")
    ResponseEntity<Resource> picture(@PathVariable String token, @PathVariable String variant, @PathVariable String format) {
        Optional<String> storageKey = jdbc
                .sql("select storage_key from media_asset where token = :token and format = :format and published")
                .param("token", token)
                .param("format", format)
                .query(String.class)
                .optional();
        if (root == null || storageKey.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path file = root.resolve(storageKey.get()).normalize();
        if (!file.startsWith(root)) {
            return ResponseEntity.notFound().build();
        }
        try {
            // Resolving symbolic links: a link inside the folder that points outside it is refused.
            Path real = file.toRealPath();
            if (!real.startsWith(root.toRealPath()) || !Files.isRegularFile(real)) {
                return ResponseEntity.notFound().build();
            }
            file = real;
        } catch (IOException missing) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .contentType(MediaType.parseMediaType("image/" + (format.equals("jpg") ? "jpeg" : format)))
                .body(new FileSystemResource(file));
    }
}
