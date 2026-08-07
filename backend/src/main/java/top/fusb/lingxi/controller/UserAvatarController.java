package top.fusb.lingxi.controller;

import top.fusb.lingxi.service.UserAvatarService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Duration;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/public/avatars")
public class UserAvatarController {

    private final UserAvatarService userAvatarService;

    @GetMapping("/{filename}")
    public ResponseEntity<Resource> avatar(@PathVariable String filename) {
        Path file = userAvatarService.avatarPath(filename);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)))
                .contentType(contentType(filename))
                .body(new FileSystemResource(file));
    }

    private MediaType contentType(String filename) {
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (filename.endsWith(".webp")) {
            return MediaType.valueOf("image/webp");
        }
        if (filename.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        return MediaType.IMAGE_PNG;
    }
}
