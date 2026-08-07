package top.fusb.lingxi.runtime.langchain.file;

import top.fusb.lingxi.runtime.langchain.agent.LangChainExecutionContext;
import top.fusb.lingxi.runtime.langchain.config.LangChainRuntimeProperties;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LangChainManagedFileRegistry {

    public static final String TASK_ROOT = "task-context";

    private final Path taskRepositoryRoot;
    private final Path sharedRepositoryRoot;
    private final Path runtimeRoot;
    private final Map<String, Path> roots = new ConcurrentHashMap<>();

    public LangChainManagedFileRegistry(LangChainRuntimeProperties properties,
                                        LangChainExecutionContext context) {
        this(properties, context, Map.of());
    }

    public LangChainManagedFileRegistry(LangChainRuntimeProperties properties,
                                        LangChainExecutionContext context,
                                        Map<String, Path> internalRoots) {
        Path workspace = context.workspace().toAbsolutePath().normalize();
        taskRepositoryRoot = workspace.resolve(".agent-worktrees");
        sharedRepositoryRoot = Path.of(properties.getSharedRepositoryCheckoutRoot()).toAbsolutePath().normalize();
        Path taskRoot = Path.of(context.workspaceLayout().taskContextRoot()).toAbsolutePath().normalize();
        try {
            runtimeRoot = Path.of(context.workspaceLayout().runtimeRoot()).toAbsolutePath().normalize().toRealPath();
            roots.put(TASK_ROOT, taskRoot.toRealPath());
            for (Map.Entry<String, Path> entry : internalRoots.entrySet()) {
                String alias = entry.getKey() == null ? "" : entry.getKey().trim();
                if (alias.isEmpty() || TASK_ROOT.equals(alias) || entry.getValue() == null) {
                    throw new IllegalArgumentException("Runtime 文件根目录标识无效：" + alias);
                }
                Path internalRoot = entry.getValue().toAbsolutePath().normalize().toRealPath();
                if (!Files.isDirectory(internalRoot)) {
                    throw new IllegalArgumentException("Runtime 文件根目录不存在：" + internalRoot);
                }
                roots.put(alias, internalRoot);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("初始化受管文件根目录失败", exception);
        }
    }

    /**
     * 将能力返回的代码 worktree 注册为通用文件工具可访问的根目录。
     *
     * @param worktreePath 任务 worktree 或共享 checkout 的绝对路径
     * @return 解析符号链接后的文件根目录
     * @throws Exception 路径不存在、越界或不是 Git worktree 时抛出
     */
    public Path registerExternalRoot(String rootPath, boolean repositoryRequired) throws Exception {
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalArgumentException("资源根目录不能为空");
        }
        Files.createDirectories(taskRepositoryRoot);
        Files.createDirectories(sharedRepositoryRoot);
        Path root = Path.of(rootPath).toAbsolutePath().normalize().toRealPath();
        boolean managedPath = root.startsWith(taskRepositoryRoot.toRealPath())
                || root.startsWith(sharedRepositoryRoot.toRealPath());
        if (!managedPath || !Files.isDirectory(root)
                || repositoryRequired && !Files.exists(root.resolve(".git"))) {
            throw new IllegalArgumentException("只能访问平台托管的资源根目录");
        }
        roots.put(root.toString(), root);
        return root;
    }

    /**
     * 解析模型传入的根目录标识，只接受平台预先注册的任务目录或能力文件根目录。
     *
     * @param root TASK_ROOT 别名或能力输出中的 fileRoot
     * @return 已注册的真实文件根目录
     * @throws Exception 根目录不存在或未注册时抛出
     */
    public Path resolveRoot(String root) throws Exception {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("root 不能为空");
        }
        Path registered = roots.get(root.trim());
        if (registered != null) {
            return registered;
        }
        try {
            Path candidate = Path.of(root).toAbsolutePath().normalize().toRealPath();
            registered = roots.get(candidate.toString());
        } catch (Exception exception) {
            throw new IllegalArgumentException("文件根目录未由当前任务注册", exception);
        }
        if (registered == null) {
            throw new IllegalArgumentException("文件根目录未由当前任务注册");
        }
        return registered;
    }

    /**
     * 在已注册根目录中解析一个普通文件，并阻止路径穿越、符号链接和平台私密目录访问。
     *
     * @param root 已通过 resolveRoot 解析的真实根目录
     * @param relativePath 根目录内相对文件路径
     * @return 可安全读取的真实普通文件
     * @throws Exception 文件不存在、路径越界或指向受限目录时抛出
     */
    public Path resolveFile(Path root, String relativePath) throws Exception {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute() || relative.startsWith(".git")) {
            throw new IllegalArgumentException("只能读取根目录内相对路径");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)
                || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("只能读取已注册根目录内的普通文件");
        }
        Path realTarget = target.toRealPath();
        if (!realTarget.startsWith(root)) {
            throw new IllegalArgumentException("只能读取已注册根目录内的普通文件");
        }
        Path taskRoot = roots.get(TASK_ROOT);
        if (root.equals(taskRoot) && realTarget.startsWith(runtimeRoot)) {
            throw new IllegalArgumentException("不能读取平台私密运行目录");
        }
        return realTarget;
    }

    boolean isRuntimePath(Path root, Path candidate) {
        Path taskRoot = roots.get(TASK_ROOT);
        return root.equals(taskRoot) && candidate.toAbsolutePath().normalize().startsWith(runtimeRoot);
    }
}
