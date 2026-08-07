package top.fusb.lingxi.demo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.fusb.lingxi.demo.service.LogService;
import top.fusb.lingxi.demo.web.LogModels.FileListView;
import top.fusb.lingxi.demo.web.LogModels.HealthView;
import top.fusb.lingxi.demo.web.LogModels.TailView;

@RestController
@RequestMapping("/files/logs")
@RequiredArgsConstructor
public class HttpLogController {

    private final LogService logService;

    @GetMapping("/health")
    public HealthView health(@RequestParam String root) {
        return logService.health(root);
    }

    @GetMapping("/list")
    public FileListView list(@RequestParam String root,
                             @RequestParam(required = false) String path,
                             @RequestParam(defaultValue = "*.log") String pattern,
                             @RequestParam(defaultValue = "200") int limit) {
        return logService.listFiles(root, path, pattern, limit);
    }

    @GetMapping("/tail")
    public TailView tail(@RequestParam String root, @RequestParam String file,
                         @RequestParam(defaultValue = "524288") int bytes,
                         @RequestParam(defaultValue = "UTF-8") String encoding) {
        return logService.tail(root, file, bytes);
    }
}
