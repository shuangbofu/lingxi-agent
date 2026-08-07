package top.fusb.lingxi.demo.web;

import java.time.LocalDateTime;
import java.util.List;

public final class LogModels {

    private LogModels() {
    }

    public record HealthView(String status, String root, int streamCount) {
    }

    public record FileItem(String path, String name, boolean directory, long size,
                           LocalDateTime modifiedAt) {
    }

    public record FileListView(String root, List<FileItem> items) {
    }

    public record TailView(String file, int bytes, boolean truncated, String content) {
    }
}
