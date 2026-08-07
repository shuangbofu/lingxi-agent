package top.fusb.lingxi.demo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import top.fusb.lingxi.demo.repository.ProjectRepository;
import top.fusb.lingxi.demo.service.DocumentService;
import top.fusb.lingxi.demo.service.LogService;
import top.fusb.lingxi.demo.service.ProjectService;
import top.fusb.lingxi.demo.web.ApiModels.DocumentInput;
import top.fusb.lingxi.demo.web.ApiModels.EnvironmentInput;
import top.fusb.lingxi.demo.web.ApiModels.LogInput;
import top.fusb.lingxi.demo.web.ApiModels.ProjectInput;
import top.fusb.lingxi.demo.web.ApiModels.ProjectView;
import top.fusb.lingxi.demo.web.ApiModels.RepositoryInput;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements CommandLineRunner {

    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final DocumentService documentService;
    private final LogService logService;

    @Override
    public void run(String... args) {
        if (projectRepository.count() > 0) {
            return;
        }

        ProjectView codex = projectService.saveProject(null, new ProjectInput(
                "openai-codex", "OpenAI Codex", "OpenAI 开源的本地编程 Agent，可用于体验真实仓库拉取和代码分析。",
                List.of(new EnvironmentInput(null, "main", "开源主线", "main",
                        List.of(new RepositoryInput(null, "codex", "OpenAI Codex", "官方公开仓库",
                                "https://github.com/openai/codex.git", "main", true)), List.of())),
                List.of()));

        projectService.saveProject(null, new ProjectInput(
                "openhands", "OpenHands", "开源的软件开发 Agent，可用于分析 Agent、Runtime 和工具执行流程。",
                List.of(new EnvironmentInput(null, "main", "开源主线", "main",
                        List.of(new RepositoryInput(null, "openhands", "OpenHands", "官方公开仓库",
                                "https://github.com/All-Hands-AI/OpenHands.git", "main", true)), List.of())),
                List.of()));

        projectService.saveProject(null, new ProjectInput(
                "cline", "Cline", "运行在编辑器中的开源编程 Agent，可用于分析工具调用、上下文管理和任务执行。",
                List.of(new EnvironmentInput(null, "main", "开源主线", "main",
                        List.of(new RepositoryInput(null, "cline", "Cline", "官方公开仓库",
                                "https://github.com/cline/cline.git", "main", true)), List.of())),
                List.of()));

        documentService.saveDocument(null, new DocumentInput(
                "openai-codex", "Codex 项目阅读线索", "开源项目/Codex.md", "代码分析",
                "# Codex 项目阅读线索\n\n仓库地址为 `https://github.com/openai/codex.git`，默认分析分支为 `main`。\n\n"
                        + "## 建议关注\n\n可以从 CLI 入口、Agent 执行循环、工具调用、沙箱与审批、MCP 接入几个方向阅读代码。"
                        + "具体实现结论应以任务实际拉取的仓库代码为依据，本页只提供检索入口和分析范围。"));
        documentService.saveDocument(null, new DocumentInput(
                "openhands", "OpenHands 项目阅读线索", "开源项目/OpenHands.md", "代码分析",
                "# OpenHands 项目阅读线索\n\n仓库地址为 `https://github.com/All-Hands-AI/OpenHands.git`，默认分析分支为 `main`。\n\n"
                        + "## 建议关注\n\n可以从 Agent 执行循环、Runtime、事件流、工具调用和前后端交互几个方向阅读代码。"
                        + "具体模块关系应以任务实际拉取的仓库代码为依据。"));
        documentService.saveDocument(null, new DocumentInput(
                "cline", "Cline 项目阅读线索", "开源项目/Cline.md", "代码分析",
                "# Cline 项目阅读线索\n\n仓库地址为 `https://github.com/cline/cline.git`，默认分析分支为 `main`。\n\n"
                        + "## 建议关注\n\n可以从编辑器入口、任务状态、上下文管理、工具审批和 MCP 接入几个方向阅读代码。"
                        + "具体实现结论应以任务实际拉取的仓库代码为依据。"));

        LocalDateTime eventTime = LocalDateTime.now().minusMinutes(5);
        logService.saveLog(new LogInput(codex.id(), "main", "agent-runner", eventTime,
                "INFO", "demo-trace-1001", "开始分析公开仓库 repository=openai/codex branch=main"));
        logService.saveLog(new LogInput(codex.id(), "main", "agent-runner", eventTime.plusSeconds(1),
                "INFO", "demo-trace-1001", "仓库拉取完成，开始读取 Agent 执行入口"));
        logService.saveLog(new LogInput(codex.id(), "main", "agent-runner", eventTime.plusSeconds(2),
                "ERROR", "demo-trace-1001", "模型请求超时，代码分析任务中断 timeoutSeconds=60"));
        log.info("Initialized public Lingxi resource demo data");
    }
}
