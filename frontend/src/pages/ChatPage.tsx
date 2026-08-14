import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, MouseEvent as ReactMouseEvent, UIEvent } from 'react';
import '../styles/chat.css';
import '../styles/task-workspace.css';
import { Button, Dropdown, Empty, Input, Modal, Popover, Select, Spin, message } from 'antd';
import type { ItemType } from 'antd/es/menu/interface';
import { ArrowCounterClockwise, ChatsCircle, ChartBar, Cpu, FunnelSimple, MagnifyingGlass, Sidebar, SignOut, SquaresFour, Stop, UserCircle, X } from '@phosphor-icons/react';
import { Navigate, useLocation, useNavigate, useParams } from 'react-router-dom';
import { cancelTask, continueTaskRound, getTask, listTaskRounds, listUserVisibleScenarios, pageTasks, resumeTask, retryTask } from '../api/lingxi';
import { AppBrandText } from '../components/AppBrandText';
import { AppLogo } from '../components/AppLogo';
import { AppTag, TaskStatusTag } from '../components/AppTag';
import { DefinitionIcon } from '../components/DefinitionIcon';
import { ModeControl } from '../components/ModeControl';
import { RuntimeModeTag } from '../components/RuntimeModeTag';
import { TaskModelTag } from '../components/TaskModelTag';
import { TaskDownloadPdfButton } from '../components/TaskDownloadPdfButton';
import { TaskResumeModal } from '../components/TaskResumeModal';
import { TaskShareButton } from '../components/TaskShareButton';
import { TaskUserFloat } from '../components/TaskUserFloat';
import { ThemeControl } from '../components/ThemeControl';
import { UserAvatar } from '../components/UserAvatar';
import { ConversationRound, buildTaskOutput, displayTaskQuestion, hasTaskResult, roundElementId } from '../components/conversation/ConversationRound';
import { useAuth } from '../context/AuthContext';
import { useRuntimeModes } from '../hooks/useRuntimeModes';
import { useTaskEventStream } from '../hooks/useTaskEventStream';
import type { AgentRuntimeDescriptor, AgentScenario, TaskItem, TaskRoundSummary, TaskStatus } from '../types/api';
import { loginPathWithRedirect } from '../utils/authRedirect';
import { formatRelativeTime, formatTime, formatTokenCount, statusText } from '../utils/format';
import { latestRound, mergeRoundList, mergeTaskDetail } from '../utils/conversation';
import { isActiveTaskStatus } from '../utils/taskStatus';
import { taskDefinitionIconUrl } from '../utils/taskVisual';
import { AskPage } from './AskPage';
import { ModelManagementPage } from './ModelManagementPage';
import { ProfilePage } from './ProfilePage';
import { TaskExecutionReportPage } from './TaskExecutionReportPage';

const CONVERSATION_SIZE = 50;
const SIDEBAR_COLLAPSED_KEY = 'workbench:chat:sidebar-collapsed';
const SIDEBAR_WIDTH_KEY = 'workbench:chat:sidebar-width';
const SIDEBAR_MIN_WIDTH = 220;
const SIDEBAR_MAX_WIDTH = 440;

/**
 * 对话形态页面：左侧可折叠面板（品牌 + 会话列表 + 账户工具）+ 右侧对话区。
 * 路由：/chat（无选中会话，显示原提问页）与 /chat/:rootTaskId（选中会话）。
 */
export function ChatPage() {
  const { rootTaskId } = useParams<{ rootTaskId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, hasPermission } = useAuth();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => window.localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1');
  const [sidebarWidth, setSidebarWidth] = useState(() => {
    const stored = Number(window.localStorage.getItem(SIDEBAR_WIDTH_KEY));
    return Number.isFinite(stored) && stored >= SIDEBAR_MIN_WIDTH && stored <= SIDEBAR_MAX_WIDTH ? stored : 280;
  });
  const sidebarWidthRef = useRef(sidebarWidth);
  useEffect(() => {
    sidebarWidthRef.current = sidebarWidth;
  }, [sidebarWidth]);
  const [profileOpen, setProfileOpen] = useState(false);
  const [modelsOpen, setModelsOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [status, setStatus] = useState<TaskStatus>();
  const [runtimeCode, setRuntimeCode] = useState<string>();
  const [modelProfileId, setModelProfileId] = useState<string>();
  const [sortBy, setSortBy] = useState<'updatedAt' | 'createdAt'>('updatedAt');
  const [conversations, setConversations] = useState<TaskItem[]>([]);
  const [conversationsLoading, setConversationsLoading] = useState(false);
  const [conversationsPage, setConversationsPage] = useState(1);
  const [conversationsHasMore, setConversationsHasMore] = useState(true);
  const [rounds, setRounds] = useState<TaskRoundSummary[]>([]);
  const [roundDetails, setRoundDetails] = useState<Record<number, TaskItem>>({});
  const [activeTask, setActiveTask] = useState<TaskItem>();
  const [activeRoundId, setActiveRoundId] = useState<number>();
  const [loading, setLoading] = useState(false);
  const [scenarios, setScenarios] = useState<AgentScenario[]>([]);
  const [retrying, setRetrying] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);
  const runtimeModes = useRuntimeModes();
  const requestRef = useRef(0);
  const messagesRef = useRef<HTMLDivElement>(null);
  const shouldFollowLatestRef = useRef(true);

  const activeRootId = useMemo(() => {
    if (rootTaskId) {
      const parsed = Number(rootTaskId);
      return Number.isFinite(parsed) ? parsed : undefined;
    }
    return undefined;
  }, [rootTaskId]);

  const active = Boolean(activeTask && isActiveTaskStatus(activeTask.status));
  const conversationTasks = useMemo(() =>
    rounds.map((round) => roundDetails[round.id]).filter((item): item is TaskItem => Boolean(item)),
    [rounds, roundDetails]);
  const currentOutput = activeTask ? buildTaskOutput(activeTask) : { process: '', result: '' };
  const currentHasResult = activeTask ? hasTaskResult(activeTask, currentOutput.result) : false;
  const modelOptions = useMemo(() => Array.from(new Map(
    runtimeModes
      .filter((runtime) => !runtimeCode || runtime.code === runtimeCode)
      .flatMap((runtime) => runtime.models)
      .map((model) => [model.id, { label: model.name, value: model.id }] as const),
  ).values()), [runtimeCode, runtimeModes]);
  const statusOptions = useMemo(() =>
    (['PENDING', 'RUNNING', 'WAITING_USER', 'SUCCESS', 'FAILED', 'CANCELED'] as TaskStatus[])
      .map((value) => ({ label: statusText(value), value })),
    []);
  const hasFilters = Boolean(query || status || runtimeCode || modelProfileId || sortBy !== 'updatedAt');

  const loadConversations = useCallback(async () => {
    setConversationsLoading(true);
    try {
      const result = await pageTasks({
        page: 1,
        size: CONVERSATION_SIZE,
        scope: 'mine',
        sortBy,
        aggregateConversation: true,
        query: query || undefined,
        status,
        runtimeCode,
        modelProfileId,
      });
      setConversations(result.records || []);
      setConversationsPage(1);
      setConversationsHasMore((result.records?.length || 0) >= CONVERSATION_SIZE);
    } finally {
      setConversationsLoading(false);
    }
  }, [query, status, runtimeCode, modelProfileId, sortBy]);

  const loadMoreConversations = useCallback(async () => {
    if (conversationsLoading || !conversationsHasMore) {
      return;
    }
    setConversationsLoading(true);
    try {
      const nextPage = conversationsPage + 1;
      const result = await pageTasks({
        page: nextPage,
        size: CONVERSATION_SIZE,
        scope: 'mine',
        sortBy,
        aggregateConversation: true,
        query: query || undefined,
        status,
        runtimeCode,
        modelProfileId,
      });
      setConversations((current) => [...current, ...(result.records || [])]);
      setConversationsPage(nextPage);
      setConversationsHasMore((result.records?.length || 0) >= CONVERSATION_SIZE);
    } finally {
      setConversationsLoading(false);
    }
  }, [conversationsLoading, conversationsHasMore, conversationsPage, query, status, runtimeCode, modelProfileId, sortBy]);

  function handleSidebarScroll(event: UIEvent<HTMLDivElement>) {
    const el = event.currentTarget;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 100) {
      void loadMoreConversations();
    }
  }

  const loadConversation = useCallback(async (rootId: number) => {
    const requestId = requestRef.current + 1;
    requestRef.current = requestId;
    setLoading(true);
    try {
      const [rootDetail, listedRounds] = await Promise.all([
        getTask(rootId, { includeEvents: true }),
        listTaskRounds(rootId),
      ]);
      if (requestRef.current !== requestId) {
        return;
      }
      const merged = mergeRoundList(listedRounds, rootDetail);
      const details = await Promise.all(merged.map(async (round) => {
        if (round.id === rootDetail.id) {
          return rootDetail;
        }
        return getTask(round.id, { includeEvents: true });
      }));
      if (requestRef.current !== requestId) {
        return;
      }
      const byId: Record<number, TaskItem> = {};
      details.forEach((detail) => {
        byId[detail.id] = detail;
      });
      setRounds(merged);
      setRoundDetails(byId);
      const latest = latestRound(merged);
      setActiveRoundId(latest?.id);
      setActiveTask(latest ? byId[latest.id] : rootDetail);
    } finally {
      if (requestRef.current === requestId) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void loadConversations();
  }, [loadConversations]);

  useEffect(() => {
    void listUserVisibleScenarios().then(setScenarios).catch(() => {});
  }, []);

  useEffect(() => {
    if (activeRootId !== undefined) {
      void loadConversation(activeRootId).catch(() => {});
    } else {
      // 无选中会话（新对话）：清空上一个会话的展示状态
      requestRef.current += 1;
      setRounds([]);
      setRoundDetails({});
      setActiveTask(undefined);
      setLoading(false);
    }
  }, [activeRootId, loadConversation]);

  // 活动任务轮询兜底
  useEffect(() => {
    if (!activeTask || !isActiveTaskStatus(activeTask.status)) {
      return;
    }
    const timer = window.setInterval(() => {
      void getTask(activeTask.id, { includeEvents: true })
        .then((detail) => setActiveTask((current) => mergeTaskDetail(current, detail)))
        .catch(() => {});
    }, 2500);
    return () => window.clearInterval(timer);
  }, [activeTask?.id, activeTask?.status]);

  // 实时详情同步到轮次详情缓存
  useEffect(() => {
    if (!activeTask) {
      return;
    }
    setRoundDetails((current) => ({ ...current, [activeTask.id]: activeTask }));
  }, [activeTask]);

  const handleComplete = useCallback(async (taskId: number) => {
    try {
      const detail = await getTask(taskId, { includeEvents: true });
      setActiveTask((current) => (current && current.id === taskId ? mergeTaskDetail(current, detail) : current));
    } catch {
      // 刷新失败时保持当前状态
    }
    void loadConversations();
  }, [loadConversations]);

  useTaskEventStream(activeTask, setActiveTask, handleComplete, true);

  // 活动任务时消息流贴底跟随
  useEffect(() => {
    const container = messagesRef.current;
    if (!container || !active) {
      return;
    }
    shouldFollowLatestRef.current = true;
    const follow = () => {
      if (!shouldFollowLatestRef.current) {
        return;
      }
      window.requestAnimationFrame(() => {
        if (shouldFollowLatestRef.current) {
          container.scrollTop = container.scrollHeight;
        }
      });
    };
    follow();
    const observer = new ResizeObserver(follow);
    observer.observe(container.firstElementChild || container);
    return () => observer.disconnect();
  }, [active, activeTask?.id]);

  function handleMessagesScroll(event: UIEvent<HTMLDivElement>) {
    const container = event.currentTarget;
    const distanceFromBottom = container.scrollHeight - container.scrollTop - container.clientHeight;
    shouldFollowLatestRef.current = distanceFromBottom < 120;
    const containerTop = container.getBoundingClientRect().top;
    let closestId = activeRoundId;
    let closestDistance = Number.MAX_SAFE_INTEGER;
    for (const round of conversationTasks) {
      const element = document.getElementById(roundElementId(round.id));
      if (!element) {
        continue;
      }
      const distance = Math.abs(element.getBoundingClientRect().top - containerTop - 24);
      if (distance < closestDistance) {
        closestDistance = distance;
        closestId = round.id;
      }
    }
    if (closestId && closestId !== activeRoundId) {
      setActiveRoundId(closestId);
    }
  }

  function scrollToRound(taskId: number) {
    const container = messagesRef.current;
    const element = document.getElementById(roundElementId(taskId));
    if (!container || !element) {
      return;
    }
    const distance = element.getBoundingClientRect().top - container.getBoundingClientRect().top;
    container.scrollTo({ top: container.scrollTop + distance - 18, behavior: 'smooth' });
  }

  async function refreshActiveTask(taskId: number) {
    try {
      const detail = await getTask(taskId, { includeEvents: true });
      setActiveTask((current) => mergeTaskDetail(current, detail));
    } catch {
      // 忽略刷新失败
    }
  }

  async function handleContinue(content: string, attachmentIds: string[]) {
    if (!activeTask) {
      return;
    }
    const result = await continueTaskRound(activeTask.id, { userInput: content, attachmentIds });
    shouldFollowLatestRef.current = true;
    setRounds((current) => mergeRoundList(current, result));
    setRoundDetails((current) => ({ ...current, [result.id]: result }));
    setActiveTask(result);
    void loadConversations();
  }

  async function handleCancel() {
    if (!activeTask) {
      return;
    }
    await cancelTask(activeTask.id);
    await refreshActiveTask(activeTask.id);
    void loadConversations();
  }

  async function handleRetry() {
    if (!activeTask) {
      return;
    }
    setRetrying(true);
    try {
      const result = await retryTask(activeTask.id);
      if (result.id !== activeTask.id) {
        navigate(`/chat/${result.conversationRootTaskId || result.id}`, { replace: true });
        return;
      }
      await loadConversation(result.conversationRootTaskId || result.id);
    } finally {
      setRetrying(false);
    }
  }

  async function handleResume(userInput?: string) {
    if (!activeTask) {
      return;
    }
    setRetrying(true);
    try {
      const result = await resumeTask(activeTask.id, userInput ? { userInput } : {});
      setResumeOpen(false);
      if (result.id !== activeTask.id) {
        navigate(`/chat/${result.conversationRootTaskId || result.id}`, { replace: true });
        return;
      }
      await loadConversation(result.conversationRootTaskId || result.id);
    } finally {
      setRetrying(false);
    }
  }

  function handleAskCreated(result: TaskItem) {
    message.success('已开始处理');
    void loadConversations();
    navigate(`/chat/${result.conversationRootTaskId || result.id}`);
  }

  function toggleSidebar() {
    setSidebarCollapsed((current) => {
      const next = !current;
      window.localStorage.setItem(SIDEBAR_COLLAPSED_KEY, next ? '1' : '0');
      return next;
    });
  }

  function startSidebarResize(event: ReactMouseEvent<HTMLDivElement>) {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = sidebarWidthRef.current;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    const onMove = (moveEvent: MouseEvent) => {
      const next = Math.min(SIDEBAR_MAX_WIDTH, Math.max(SIDEBAR_MIN_WIDTH, startWidth + (moveEvent.clientX - startX)));
      setSidebarWidth(next);
    };
    const onUp = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      window.localStorage.setItem(SIDEBAR_WIDTH_KEY, String(sidebarWidthRef.current));
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }

  function handleBrandClick() {
    if (sidebarCollapsed) {
      toggleSidebar();
      return;
    }
    navigate('/chat');
  }

  function handleAccountMenu(key: string) {
    if (key === 'models') {
      setModelsOpen(true);
      return;
    }
    if (key === 'profile') {
      setProfileOpen(true);
      return;
    }
    if (key === 'logout') {
      void logout().then(() => navigate('/login', { replace: true }));
    }
  }

  if (!user?.authenticated) {
    return <Navigate to={loginPathWithRedirect(location)} replace />;
  }

  const displayName = user?.displayName || user?.username || '未登录';
  const accountMenuItems: ItemType[] = [
    { key: 'models', label: '模型配置', icon: <Cpu size={16} weight="fill" /> },
    { key: 'profile', label: '个人中心', icon: <UserCircle size={16} weight="fill" /> },
    { key: 'logout', label: '退出登录', icon: <SignOut size={16} weight="fill" /> },
  ];
  const firstAdminMenuPath = user?.menus?.find((item) => item.path !== '/')?.path || '/admin';
  const selectedConversationId = activeTask ? (activeTask.conversationRootTaskId || activeTask.id) : undefined;
  const activeRoundDetail = activeRoundId ? roundDetails[activeRoundId] : activeTask;
  const activeRoundNo = activeRoundDetail?.roundNo || rounds.findIndex((round) => round.id === activeRoundId) + 1 || 1;
  const activeRoundToolCalls = activeRoundDetail?.events?.filter((event) => event.type === 'COMMAND').length;
  const activeRoundToolDurationMs = activeRoundDetail?.executionMetrics?.commandDurationMs;
  const activeRoundModelDurationMs = activeRoundDetail?.executionMetrics?.modelDurationMs;
  const activeRoundCacheRate = activeRoundDetail?.inputTokens && activeRoundDetail.inputTokens > 0
    ? (activeRoundDetail.cachedInputTokens || 0) / activeRoundDetail.inputTokens
    : undefined;
  const totalRounds = conversationTasks.length;

  return (
    <div
      className={sidebarCollapsed ? 'chat-page chat-page-sidebar-collapsed' : 'chat-page'}
      style={{ '--chat-sidebar-width': `${sidebarWidth}px` } as CSSProperties}
    >
      <aside className="chat-sidebar">
        <div className="chat-sidebar-brand">
          <button className="chat-sidebar-brand-button" type="button" onClick={handleBrandClick} title={sidebarCollapsed ? '展开面板' : '新对话'}>
            <span className="chat-sidebar-brand-logo"><AppLogo size={34} /></span>
            <AppBrandText className="chat-sidebar-brand-name" />
            <span className="chat-sidebar-brand-expand" aria-hidden="true"><Sidebar size={17} weight="bold" /></span>
          </button>
          {!sidebarCollapsed && (
            <button
              className="chat-sidebar-collapse"
              type="button"
              aria-label="收起面板"
              title="收起面板"
              onClick={toggleSidebar}
            >
              <Sidebar size={17} />
            </button>
          )}
        </div>
        {sidebarCollapsed ? (
          <div className="chat-sidebar-body chat-sidebar-body-collapsed">
            <button
              className="chat-sidebar-foot-item"
              type="button"
              title="新对话"
              aria-label="新对话"
              onClick={() => navigate('/chat')}
            >
              <ChatsCircle size={17} weight="fill" />
            </button>
            <button
              className="chat-sidebar-foot-item"
              type="button"
              title="搜索会话"
              aria-label="搜索会话"
              onClick={() => {
                setSearchOpen(true);
                setSidebarCollapsed(false);
              }}
            >
              <MagnifyingGlass size={17} />
            </button>
          </div>
        ) : (
          <div className="chat-sidebar-body">
            <div className="chat-sidebar-head">
              <Button className="chat-new-button" type="primary" icon={<ChatsCircle size={15} weight="fill" />} onClick={() => navigate('/chat')}>
                新对话
              </Button>
            </div>
            <div className="chat-sidebar-toolbar">
              {searchOpen ? (
                <div className="chat-search-expand">
                  <Input
                    autoFocus
                    allowClear
                    size="small"
                    className="chat-search-input"
                    prefix={<MagnifyingGlass size={14} className="chat-search-prefix" />}
                    suffix={(
                      <button
                        className="chat-search-close"
                        type="button"
                        aria-label="关闭搜索"
                        title="关闭搜索"
                        onClick={() => setSearchOpen(false)}
                      >
                        <X size={14} />
                      </button>
                    )}
                    placeholder="搜索会话"
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                  />
                </div>
              ) : (
                <>
                  <span className="chat-sidebar-title">工作区</span>
                  <div className="chat-sidebar-toolbar-actions">
                    <Button
                      className="chat-toolbar-icon"
                      type="text"
                      size="small"
                      icon={<MagnifyingGlass size={17} />}
                      aria-label="搜索会话"
                      title="搜索会话"
                      onClick={() => setSearchOpen(true)}
                    />
                    <Popover
                      trigger="click"
                      placement="bottomLeft"
                      content={
                        <div className="chat-filter-popover">
                          <Select
                            allowClear
                            size="small"
                            placeholder="全部状态"
                            value={status}
                            options={statusOptions}
                            onChange={setStatus}
                          />
                          <Select
                            allowClear
                            size="small"
                            placeholder="全部引擎"
                            value={runtimeCode}
                            options={runtimeModes.map((runtime) => ({ label: runtime.name, value: runtime.code }))}
                            onChange={(value) => {
                              setRuntimeCode(value);
                              if (value && modelProfileId && !runtimeModes
                                .find((runtime) => runtime.code === value)
                                ?.models.some((model) => model.id === modelProfileId)) {
                                setModelProfileId(undefined);
                              }
                            }}
                          />
                          <Select
                            allowClear
                            showSearch
                            optionFilterProp="label"
                            size="small"
                            placeholder="全部模型"
                            value={modelProfileId}
                            options={modelOptions}
                            onChange={setModelProfileId}
                          />
                          <Select
                            size="small"
                            value={sortBy}
                            options={[
                              { label: '按更新时间', value: 'updatedAt' },
                              { label: '按创建时间', value: 'createdAt' },
                            ]}
                            onChange={setSortBy}
                          />
                          {hasFilters && (
                            <Button size="small" type="link" onClick={() => {
                              setQuery('');
                              setStatus(undefined);
                              setRuntimeCode(undefined);
                              setModelProfileId(undefined);
                              setSortBy('updatedAt');
                            }}>重置筛选</Button>
                          )}
                        </div>
                      }
                    >
                      <Button
                        className={hasFilters ? 'chat-toolbar-icon chat-filter-button-active' : 'chat-toolbar-icon'}
                        type="text"
                        size="small"
                        icon={<FunnelSimple size={17} weight={hasFilters ? 'fill' : 'regular'} />}
                        aria-label="筛选会话"
                        title="筛选会话"
                      />
                    </Popover>
                  </div>
                </>
              )}
            </div>
            <div className="chat-sidebar-list" onScroll={handleSidebarScroll}>
              {conversationsLoading && conversations.length === 0 ? (
                <div className="chat-sidebar-empty"><Spin size="small" /></div>
              ) : conversations.length === 0 ? (
                <Empty className="chat-sidebar-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无会话" />
              ) : (
                <>
                  {conversations.map((item) => {
                    const itemRootId = item.conversationRootTaskId || item.id;
                    const selected = activeRootId === itemRootId || (activeRootId === undefined && selectedConversationId === itemRootId);
                    return (
                      <Popover
                        key={item.id}
                        trigger="hover"
                        placement="rightTop"
                        arrow={false}
                        content={<ConversationCardInfo item={item} runtimeModes={runtimeModes} />}
                      >
                        <button
                          type="button"
                          className={selected ? 'chat-sidebar-item chat-sidebar-item-active' : 'chat-sidebar-item'}
                          onClick={() => navigate(`/chat/${itemRootId}`)}
                        >
                          <span className="chat-sidebar-item-dot" data-status={item.status} aria-hidden="true" />
                          <span className="chat-sidebar-item-icon">
                            <DefinitionIcon src={taskDefinitionIconUrl(item)} label={item.scenarioName || item.scenario} size="css" />
                          </span>
                          <span className="chat-sidebar-item-main">
                            <span className="chat-sidebar-item-title">{displayTaskQuestion(item)}</span>
                            {(item.roundCount || 1) > 1 && <span className="chat-sidebar-item-rounds">{item.roundCount} 轮</span>}
                          </span>
                          <span className="chat-sidebar-item-time">{formatRelativeTime(item.updatedAt)}</span>
                        </button>
                      </Popover>
                    );
                  })}
                  {conversationsLoading && conversationsHasMore && (
                    <div className="chat-sidebar-loading"><Spin size="small" /></div>
                  )}
                  {!conversationsHasMore && conversations.length > CONVERSATION_SIZE && (
                    <div className="chat-sidebar-end">没有更多了</div>
                  )}
                </>
              )}
            </div>
          </div>
        )}
        <div className="chat-sidebar-foot">
          {user.role === 'ADMIN' && (
            <button className="chat-sidebar-foot-item" type="button" title="进入管理" aria-label="进入管理" onClick={() => navigate(firstAdminMenuPath, { state: { from: location.pathname } })}>
              <SquaresFour size={17} weight="fill" />
            </button>
          )}
          <div className="chat-sidebar-foot-item">
            <ThemeControl placement="topRight" />
          </div>
          <div className="chat-sidebar-foot-item">
            <ModeControl placement="topRight" value="chat" />
          </div>
          <Dropdown menu={{ items: accountMenuItems, onClick: ({ key }) => handleAccountMenu(String(key)) }} placement="topLeft" trigger={['click']}>
            <button className="chat-sidebar-foot-item chat-sidebar-account" type="button" title={displayName} aria-label={displayName}>
              <UserAvatar className="chat-sidebar-account-avatar" avatarUrl={user.avatarUrl} name={displayName} />
            </button>
          </Dropdown>
        </div>
      </aside>
      {!sidebarCollapsed && (
        <div
          className="chat-sidebar-resizer"
          role="separator"
          aria-orientation="vertical"
          title="拖动调整宽度"
          onMouseDown={startSidebarResize}
        />
      )}
      <section className="chat-area">
        {activeTask ? (
          <>
            <header className="chat-area-head">
              <span className="chat-area-head-icon">
                <DefinitionIcon src={taskDefinitionIconUrl(activeTask)} label={activeTask.scenarioName || activeTask.scenario} size="css" />
              </span>
              <div className="chat-area-head-meta">
                <span className="chat-area-head-title">{displayTaskQuestion(activeTask)}</span>
              </div>
              <div className="chat-area-head-actions">
                {(activeTask.roundCount || 1) > 1 && <AppTag tone="slate">{activeTask.roundCount} 轮</AppTag>}
                <RuntimeModeTag runtimeCode={activeTask.runtimeCode} runtimeModes={runtimeModes} />
                <TaskModelTag modelProviderName={activeTask.modelProviderName} modelName={activeTask.modelName} modelIdentifier={activeTask.modelIdentifier} />
                <TaskStatusTag status={activeTask.status} />
                {hasPermission('TASK_ADMIN') && (
                  <Button size="small" icon={<ChartBar size={14} weight="fill" />} onClick={() => setReportOpen(true)}>分析报告</Button>
                )}
                <TaskShareButton size="small" task={activeTask} disabled={active || !currentHasResult} label="分享" />
                <TaskDownloadPdfButton size="small" task={activeTask} disabled={active || !currentHasResult} />
              </div>
            </header>
            <div className="chat-messages-wrap">
              {rounds.length > 1 && (
                <RoundRail
                  rounds={rounds}
                  activeRoundId={activeRoundId}
                  onSelect={scrollToRound}
                />
              )}
              <div ref={messagesRef} className="chat-messages" onScroll={handleMessagesScroll}>
                {loading && conversationTasks.length === 0 ? (
                  <div className="chat-area-empty"><Spin /></div>
                ) : (
                  <>
                    <div className="run-conversation">
                      {conversationTasks.map((round, index) => (
                        <ConversationRound
                          key={round.id}
                          task={round}
                          isLatest={index === conversationTasks.length - 1}
                          scenarios={scenarios}
                        />
                      ))}
                    </div>
                    {(active || activeTask.status === 'FAILED' || activeTask.status === 'CANCELED') && (
                      <div className="chat-round-actions">
                        {active && (
                          <Button size="small" danger icon={<Stop size={14} weight="fill" />} onClick={() => void handleCancel()}>取消</Button>
                        )}
                        {activeTask.status === 'FAILED' && (
                          <Button size="small" loading={retrying} icon={<ArrowCounterClockwise size={14} weight="bold" />} onClick={() => void handleRetry()}>重试</Button>
                        )}
                        {activeTask.status === 'CANCELED' && (
                          <Button size="small" loading={retrying} icon={<ArrowCounterClockwise size={14} weight="bold" />} onClick={() => setResumeOpen(true)}>恢复</Button>
                        )}
                      </div>
                    )}
                  </>
                )}
              </div>
            </div>
            <TaskUserFloat
              mode="composer"
              taskId={activeTask.id}
              taskStatus={activeTask.status}
              userInput={activeTask.userInput}
              attachments={activeTask.attachments}
              interactions={activeTask.interactions}
              inputLabel="我"
              onAnswered={() => refreshActiveTask(activeTask.id)}
              onContinue={handleContinue}
            />
            {totalRounds > 0 && (
              <div className="chat-round-info">
                <span>共 {totalRounds} 轮</span>
                <span className="chat-round-info-sep">·</span>
                <span>当前第 {activeRoundNo} 轮</span>
                {activeRoundDetail?.requestCount !== undefined && (
                  <>
                    <span className="chat-round-info-sep">·</span>
                    <span>模型调用 {activeRoundDetail.requestCount} 次</span>
                  </>
                )}
                {activeRoundToolCalls !== undefined && (
                  <>
                    <span className="chat-round-info-sep">·</span>
                    <span>工具调用 {activeRoundToolCalls} 次</span>
                  </>
                )}
                {activeRoundToolDurationMs !== undefined && (
                  <>
                    <span className="chat-round-info-sep">·</span>
                    <span>工具耗时 {formatMillis(activeRoundToolDurationMs)}</span>
                  </>
                )}
                {activeRoundModelDurationMs !== undefined && activeRoundModelDurationMs > 0 && (
                  <>
                    <span className="chat-round-info-sep">·</span>
                    <span>LLM 耗时 {formatMillis(activeRoundModelDurationMs)}</span>
                  </>
                )}
                {activeRoundDetail?.totalTokens !== undefined && (
                  <>
                    <span className="chat-round-info-sep">·</span>
                    <span>Token {formatTokenCount(activeRoundDetail.totalTokens)}</span>
                  </>
                )}
                {activeRoundCacheRate !== undefined && (
                  <>
                    <span className="chat-round-info-sep">·</span>
                    <span>缓存命中率 {formatPercent(activeRoundCacheRate)}</span>
                  </>
                )}
              </div>
            )}
          </>
        ) : loading ? (
          <div className="chat-area-empty"><Spin /></div>
        ) : (
          <div className="chat-ask-embedded">
            <AskPage embedded onCreated={handleAskCreated} />
          </div>
        )}
      </section>
      {activeTask && (
        <TaskResumeModal
          open={resumeOpen}
          task={activeTask}
          loading={retrying}
          onCancel={() => setResumeOpen(false)}
          onSubmit={(userInput) => handleResume(userInput)}
        />
      )}
      <Modal
        className="chat-overlay-modal"
        title="个人中心"
        open={profileOpen}
        footer={null}
        width={900}
        centered
        destroyOnClose
        onCancel={() => setProfileOpen(false)}
      >
        <ProfilePage embedded />
      </Modal>
      <Modal
        className="chat-overlay-modal"
        title="模型配置"
        open={modelsOpen}
        footer={null}
        width={900}
        centered
        destroyOnClose
        onCancel={() => setModelsOpen(false)}
      >
        <ModelManagementPage scope="PERSONAL" embedded />
      </Modal>
      <Modal
        className="chat-overlay-modal"
        title="分析报告"
        open={reportOpen}
        footer={null}
        width={900}
        centered
        destroyOnClose
        onCancel={() => setReportOpen(false)}
      >
        {activeTask && <TaskExecutionReportPage taskId={activeTask.id} embedded />}
      </Modal>
    </div>
  );
}

/**
 * 对话区左侧轮次导航横条：
 * 1. 默认白色小横条；2. hover 哪个哪个往右变长；3. hover 时右侧弹出卡片（标题 + 部分内容）。
 */
function RoundRail({ rounds, activeRoundId, onSelect }: {
  rounds: TaskRoundSummary[];
  activeRoundId?: number;
  onSelect: (taskId: number) => void;
}) {
  const [hoveredId, setHoveredId] = useState<number>();
  const itemRefs = useRef<Map<number, HTMLButtonElement>>(new Map());
  const hoveredRound = rounds.find((round) => round.id === hoveredId);
  const hoveredTop = hoveredId ? itemRefs.current.get(hoveredId)?.offsetTop : undefined;
  return (
    <div className="chat-round-rail">
      {rounds.map((round) => {
        const active = round.id === activeRoundId;
        return (
          <button
            key={round.id}
            type="button"
            ref={(el) => {
              if (el) {
                itemRefs.current.set(round.id, el);
              } else {
                itemRefs.current.delete(round.id);
              }
            }}
            className={active ? 'chat-round-rail-item chat-round-rail-item-active' : 'chat-round-rail-item'}
            onMouseEnter={() => setHoveredId(round.id)}
            onMouseLeave={() => setHoveredId(undefined)}
            onClick={() => onSelect(round.id)}
          >
            <span className="chat-round-rail-bar" />
          </button>
        );
      })}
      {hoveredRound && hoveredTop !== undefined && (
        <div className="chat-round-rail-panel" style={{ top: hoveredTop }}>
          <div className="chat-round-rail-panel-title">
            {hoveredRound.userInput?.trim() || `第 ${hoveredRound.roundNo || 1} 轮`}
          </div>
          {hoveredRound.userInput?.trim() && (
            <div className="chat-round-rail-panel-content">{hoveredRound.userInput}</div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 毫秒格式化：1234 → 1.2s；123456 → 2m 3s。
 */
function formatMillis(ms?: number) {
  if (ms === undefined || ms === null || ms <= 0) {
    return '-';
  }
  const seconds = ms / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(1)}s`;
  }
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  return `${minutes}m ${rest}s`;
}

function formatPercent(rate?: number) {
  if (rate === undefined || rate === null || !Number.isFinite(rate)) {
    return '-';
  }
  return `${(Math.min(1, Math.max(0, rate)) * 100).toFixed(0)}%`;
}

/**
 * 会话卡片 hover 信息浮层：完整标题 + 状态/轮数 + 场景/引擎/模型/时间。
 */
function ConversationCardInfo({ item, runtimeModes }: { item: TaskItem; runtimeModes: AgentRuntimeDescriptor[] }) {
  const runtime = runtimeModes.find((mode) => mode.code === item.runtimeCode);
  return (
    <div className="chat-card-popover">
      <div className="chat-card-popover-title">{displayTaskQuestion(item)}</div>
      <div className="chat-card-popover-meta">
        <TaskStatusTag status={item.status} />
        {(item.roundCount || 1) > 1 && <span>{item.roundCount} 轮</span>}
        <span>{statusText(item.status)}</span>
      </div>
      <div className="chat-card-popover-rows">
        <div><span>场景</span><b>{item.scenarioName || item.scenario || '-'}</b></div>
        <div><span>引擎</span><b>{runtime?.name || item.runtimeCode || '-'}</b></div>
        <div><span>模型</span><b>{item.modelName || item.modelIdentifier || '-'}</b></div>
        <div><span>创建</span><b>{formatTime(item.createdAt)}</b></div>
        <div><span>更新</span><b>{formatTime(item.updatedAt)}</b></div>
      </div>
    </div>
  );
}
