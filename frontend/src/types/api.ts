export interface ApiResult<T> {
  success?: boolean;
  code: string;
  subCode: string;
  message: string;
  data: T;
}

export type UserRole = 'ADMIN' | 'USER';

export interface MenuItem {
  code: string;
  label: string;
  shortLabel: string;
  path: string;
  icon: string;
  tone: string;
  permission: string;
}

export interface CurrentUser {
  authenticated: boolean;
  id?: number;
  username?: string;
  displayName?: string;
  avatarUrl?: string;
  role?: UserRole;
  roleName?: string;
  permissions: string[];
  menus: MenuItem[];
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface InitialAdminSetupRequest {
  password: string;
}

export interface PasswordChangeRequest {
  oldPassword: string;
  newPassword: string;
}

export interface UserUsageQuotaPeriod {
  tokenLimit?: number;
  tokenUsed: number;
  tokenRemaining?: number;
  usagePercent?: number;
  limited: boolean;
  exceeded: boolean;
  inherited: boolean;
}

export interface UserUsageQuota {
  daily: UserUsageQuotaPeriod;
  weekly: UserUsageQuotaPeriod;
  monthly: UserUsageQuotaPeriod;
}

export interface Profile {
  id: number;
  username: string;
  displayName: string;
  avatarUrl?: string;
  role: UserRole;
  roleName: string;
  usageQuota: UserUsageQuota;
}

export interface UserItem {
  id: number;
  username: string;
  displayName: string;
  avatarUrl?: string;
  role: UserRole;
  roleName: string;
  dailyTokenLimit?: number;
  weeklyTokenLimit?: number;
  monthlyTokenLimit?: number;
  enabled: boolean;
  lastLoginAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface UserSaveRequest {
  username: string;
  displayName: string;
  avatarUrl?: string;
  password?: string;
  role: UserRole;
  enabled?: boolean;
}

export interface UserUsageSettingsRequest {
  dailyTokenLimit: number | null;
  weeklyTokenLimit: number | null;
  monthlyTokenLimit: number | null;
}

export interface UserAvatarUploadResult {
  url: string;
}

export interface AnalysisPremiseOption {
  id: number;
  name: string;
  description?: string;
}

export interface AnalysisPremiseItem {
  id: number;
  code?: string;
  name: string;
  description?: string;
  promptText?: string;
  contextValues?: Record<string, string>;
  scenarioContextValues?: Record<string, Record<string, string>>;
  visibleScenarioCodes?: string[];
  enabled: boolean;
  globalVisible: boolean;
  sortOrder: number;
  assignedUserIds?: number[];
  createdAt: string;
  updatedAt: string;
}

export interface AnalysisPremiseSaveRequest {
  name: string;
  description?: string;
  promptText?: string;
  contextValues?: Record<string, string>;
  scenarioContextValues?: Record<string, Record<string, string>>;
  visibleScenarioCodes?: string[];
  enabled: boolean;
  globalVisible: boolean;
  sortOrder?: number;
  assignedUserIds?: number[];
}

export interface AnalysisPremiseContextParameter {
  key: string;
  name: string;
  type: string;
  description?: string;
  options?: DefinitionParameterOption[];
  defaultValue?: string;
  sourceType: string;
  sourceCode: string;
  sourceName: string;
  scenarioCode: string;
  scenarioName: string;
  sortOrder?: number;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

export type CapabilityConfigValue = Record<string, string | number | boolean | null | undefined>;

export interface CapabilityConfigItem {
  id: number;
  name: string;
  capabilityCode?: string;
  capabilityName?: string;
  description?: string;
  config?: CapabilityConfigValue;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CapabilityConfigSaveRequest {
  name: string;
  capabilityCode: string;
  description?: string;
  config?: CapabilityConfigValue;
  enabled: boolean;
}

export interface RuntimeMaintenanceStatus {
  executable: string;
  osType: RuntimeOs;
  available: boolean;
  versionText?: string;
  currentVersion?: string;
  latestVersion?: string;
  updateAvailable?: boolean;
  releaseUrl?: string;
  updateCheckError?: string;
  errorText?: string;
}

export type RuntimeOs = 'LINUX' | 'MAC' | 'WINDOWS' | 'UNKNOWN';
export type CliInstallStatus = 'IDLE' | 'RUNNING' | 'SUCCESS' | 'FAILED';
export type RuntimeModelProtocol = 'RESPONSES' | 'CHAT_COMPLETIONS';

export interface RuntimePricingTimeRange {
  start: string;
  end: string;
}

export interface RuntimePricingScheduleRule {
  name: string;
  multiplier: number;
  timeRanges: RuntimePricingTimeRange[];
}

export interface RuntimeModelPricing {
  currency: string;
  timeZone: string;
  cacheHitInputPerMillion: number;
  cacheMissInputPerMillion: number;
  outputPerMillion: number;
  scheduleRules?: RuntimePricingScheduleRule[];
}

export interface RuntimePricingPlan {
  id: string;
  name: string;
  providerId: string;
  modelProfileId?: string;
  pricing: RuntimeModelPricing;
  sortOrder?: number;
}

export interface RuntimeReasoningEffortOption {
  label: string;
  value: string;
  disablesReasoning?: boolean;
  disabled?: boolean;
}

export type RuntimeProviderType = string;

export interface RuntimeProviderTypeOption {
  value: RuntimeProviderType;
  label: string;
  icon: string;
  darkIcon?: string;
  defaultBaseUrl: string;
  defaultProtocol: RuntimeModelProtocol;
  supportedProtocols: RuntimeModelProtocol[];
  imageInputSupported: boolean;
  thinkingFieldName?: string;
  reasoningEffortOptions: RuntimeReasoningEffortOption[];
}

export interface RuntimeModelProvider {
  id: string;
  providerType: RuntimeProviderType;
  name: string;
  baseUrl: string;
  apiKeyConfigured: boolean;
  apiKeyMasked?: string;
  apiKey?: string;
  instructionPrompt?: string;
  reasoningEffortOptions: RuntimeReasoningEffortOption[];
  maxConcurrency?: number;
  enabled: boolean;
  sortOrder?: number;
}

export interface RuntimeModelProfile {
  id: string;
  name: string;
  description?: string;
  providerId: string;
  model: string;
  protocol: RuntimeModelProtocol;
  contextWindowTokens: number;
  reasoningEffort?: string;
  instructionPrompt?: string;
  maxConcurrency?: number;
  imageInputSupported: boolean;
  enabled: boolean;
  sortOrder?: number;
}

export interface RuntimeModelOption {
  id: string;
  name: string;
  description?: string;
  providerId?: string;
  providerName?: string;
  providerType?: RuntimeProviderType;
  providerTypeName?: string;
  providerIcon?: string;
  providerDarkIcon?: string;
  model: string;
  protocol: RuntimeModelProtocol;
  contextWindowTokens: number;
  reasoningEffort?: string;
  reasoningEffortLabel?: string;
  imageInputSupported: boolean;
  personal: boolean;
  available: boolean;
  unavailableReason?: string;
}

export interface RuntimeConfig {
  maxTaskConcurrency?: number;
  globalDailyTokenLimit?: number;
  globalWeeklyTokenLimit?: number;
  globalMonthlyTokenLimit?: number;
  globalBoundaryPrompt?: string;
  updatedAt?: string;
}

export interface RuntimeConfigRequest {
  maxTaskConcurrency?: number;
  globalDailyTokenLimit?: number;
  globalWeeklyTokenLimit?: number;
  globalMonthlyTokenLimit?: number;
  globalBoundaryPrompt?: string;
}

export type ModelConfigScope = 'PLATFORM' | 'PERSONAL';

export interface ModelCatalog {
  scope: ModelConfigScope;
  providers: RuntimeModelProvider[];
  models: RuntimeModelProfile[];
  pricingPlans: RuntimePricingPlan[];
  providerTypes: RuntimeProviderTypeOption[];
}

export interface AgentRuntimeDescriptor {
  code: string;
  name: string;
  description?: string;
  iconUrl?: string;
  messageStreamingSupported: boolean;
  maintenanceSupported: boolean;
  mcpSupported: boolean;
  available: boolean;
  unavailableReason?: string;
  defaultSelected: boolean;
  models: RuntimeModelOption[];
  modelProviderTypes: RuntimeProviderTypeOption[];
  supportedModelProtocols: RuntimeModelProtocol[];
  maintenancePresentation?: {
    title?: string;
    description?: string;
    installActionLabel?: string;
    unavailableTitle?: string;
  };
  modelTimingNote?: string;
}

export type McpTransport = 'STDIO' | 'STREAMABLE_HTTP';

export interface McpToolPresentation {
  label: string;
  icon?: RuntimeActionIcon;
  executionMode?: 'SERIAL' | 'READ_ONLY';
}

export interface McpServer {
  id: string;
  code: string;
  name: string;
  instructions?: string;
  transport: McpTransport;
  command?: string;
  arguments: string[];
  url?: string;
  environment: Record<string, string>;
  headers: Record<string, string>;
  runtimeCodes: string[];
  activationFeatures: string[];
  toolAllowlist: string[];
  toolPresentations: Record<string, McpToolPresentation>;
  enabled: boolean;
  builtin: boolean;
  createdAt: string;
  updatedAt: string;
}

export type McpServerRequest = Omit<McpServer, 'id' | 'createdAt' | 'updatedAt' | 'code' | 'builtin'> & { code?: string };

export interface ModelEndpointCheckResult {
  success: boolean;
  message?: string;
  models?: string[];
}

export interface ModelEndpointCheckRequest {
  modelProfileId?: string;
  providerId?: string;
  apiKey?: string;
  baseUrl?: string;
}

export interface AgentDefinitionParameter {
  id?: number;
  key: string;
  name: string;
  type: string;
  required: boolean;
  description?: string;
  options?: DefinitionParameterOption[];
  defaultValue?: string;
  visible?: boolean;
  sortOrder: number;
}

export interface AgentDefinitionGuide {
  id?: number;
  key: string;
  title: string;
  description?: string;
  content: string;
  sortOrder: number;
}

export interface DefinitionParameterOption {
  label: string;
  value: string;
}

interface AgentDefinitionBase {
  code: string;
  name: string;
  description?: string;
  icon?: string;
  iconUrl?: string;
  promptText: string;
  enabled: boolean;
  parameters: AgentDefinitionParameter[];
  guides: AgentDefinitionGuide[];
  createdAt: string;
  updatedAt: string;
}

export interface CapabilityActivationCondition {
  parameter: string;
  values: string[];
}

export interface AgentScenario extends AgentDefinitionBase {
  slogan?: string;
  scenario: TaskScenario;
  inputMode?: 'CONVERSATION' | 'FORM';
  color?: string;
  userVisible: boolean;
  sortOrder: number;
  uniqueBySource?: boolean;
  resultFormat?: string;
  resultRenderer?: string;
  presentations?: string[];
  queuePriority?: number;
  capabilities: string[];
  capabilityCommands?: Record<string, string[]>;
  capabilityConditions?: Record<string, CapabilityActivationCondition>;
  packageVersion?: string;
}

export interface ScenarioPackageInspection {
  stagingToken: string;
  code: string;
  name: string;
  version: string;
  description?: string;
  color?: string;
  update: boolean;
  currentVersion?: string;
  packageSize: number;
  packageHash: string;
  parameterCount: number;
  capabilities: string[];
}

export interface PublicScenario {
  code: string;
  name: string;
  description?: string;
  slogan?: string;
  iconUrl?: string;
  color?: string;
}

export interface LoginDemoDisplayTarget {
  name: string;
  iconUrl?: string;
  darkIconUrl?: string;
}

export interface LoginDemoTimings {
  typingIntervalMs: number;
  submitDelayMs: number;
  launchDurationMs: number;
  thinkingDelayMs: number;
  messageDelayMs: number;
  stepDelayMs: number;
  resultDelayMs: number;
  completedDelayMs: number;
  resetDelayMs: number;
}

export interface LoginDemoStepDefinition {
  label: string;
  icon: RuntimeActionIcon;
}

export interface LoginDemoDefinition {
  question: string;
  inputPlaceholder: string;
  scenarioCode: string;
  scenarioName: string;
  premiseName: string;
  runtime: LoginDemoDisplayTarget;
  model: LoginDemoDisplayTarget;
  thinkingTitle: string;
  agentMessage: string;
  steps: LoginDemoStepDefinition[];
  processedLabel: string;
  emptyProcessText: string;
  result: string;
  timings: LoginDemoTimings;
}

export interface PublicLoginResponse {
  scenarios: PublicScenario[];
  demo: LoginDemoDefinition;
  initializationRequired: boolean;
}

export interface AgentCapability extends AgentDefinitionBase {
  packageVersion?: string;
  configParameters?: AgentDefinitionParameter[];
  commands?: CapabilityPackageCommand[];
}

export interface CapabilityPackageCommand {
  code: string;
  name?: string;
  icon?: RuntimeActionIcon;
  command: string;
  description?: string;
}

export interface CapabilityPackageInspection {
  stagingToken: string;
  code: string;
  name: string;
  version: string;
  description?: string;
  update: boolean;
  currentVersion?: string;
  packageSize: number;
  packageHash: string;
  runtimeIncluded: boolean;
  configParameterCount: number;
  parameterCount: number;
  guideCount: number;
  requirements: string[];
  commands: CapabilityPackageCommand[];
}

export interface AgentCapabilityStateUpdateRequest {
  enabled: boolean;
}

export interface AgentScenarioStateUpdateRequest extends AgentCapabilityStateUpdateRequest {
  userVisible: boolean;
  capabilities: string[];
  capabilityCommands: Record<string, string[]>;
}

export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_USER' | 'SUCCESS' | 'FAILED' | 'CANCELED';
export type TaskScenario = string;

export interface ResourceMemoryMetrics {
  searchCount: number;
  hitCount: number;
  candidateCount: number;
  saveCount: number;
  createdCount: number;
  refreshedCount: number;
  expiredCount: number;
  invalidatedCount: number;
  estimatedSavedDiscoveryCalls: number;
}

export interface TaskExecutionMetrics {
  totalDurationMs?: number;
  firstFeedbackMs?: number;
  commandDurationMs: number;
  resultProcessingMs?: number;
  compactionCount: number;
  duplicateCapabilityCallCount: number;
}

export type TaskExecutionReportStepType = 'MODEL_API_WAIT' | 'MODEL_STREAMING' | 'MODEL_DECISION' | 'MODEL_GENERATION' | 'MODEL_PROCESSING' | 'ORCHESTRATION' | 'COMMAND' | 'RESULT_PROCESSING';

export interface TaskExecutionReportStep {
  id: string;
  type: TaskExecutionReportStepType;
  title: string;
  detail?: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'INFO';
  startedAt: string;
  endedAt: string;
  durationMs: number;
}

export interface TaskExecutionReportModelCall {
  id: string;
  sequence: number;
  purpose: string;
  model?: string;
  modelTimingMode?: 'STREAMING' | 'OBSERVED';
  responseKind?: 'TOOL_CALL' | 'ANSWER';
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'INFO';
  startedAt: string;
  firstResponseAt?: string;
  endedAt: string;
  firstResponseMs: number;
  generationMs: number;
  totalDurationMs: number;
  inputTokens?: number;
  cachedInputTokens?: number;
  outputTokens?: number;
  reasoningOutputTokens?: number;
  totalTokens?: number;
  estimatedInputTokens?: number;
  systemInstructionTokens?: number;
  taskInstructionTokens?: number;
  mcpInstructionTokens?: number;
  toolSchemaTokens?: number;
  conversationTokens?: number;
  toolResultTokens?: number;
  imageTokens?: number;
  costCurrency?: string;
  costAmount?: number;
  cacheHitInputCost?: number;
  cacheMissInputCost?: number;
  outputCost?: number;
  priceTier?: string;
  messageCount?: number;
  toolDefinitionCount?: number;
  toolRequestCount?: number;
  outputTokensPerSecond?: number;
  diagnosisType?: 'ERROR' | 'LARGE_INPUT' | 'SLOW_FIRST_RESPONSE' | 'SLOW_GENERATION' | 'HEAVY_REASONING' | 'NORMAL';
  diagnosis?: string;
  diagnosisDetail?: string;
  errorType?: string;
  errorMessage?: string;
}

export interface TaskExecutionReport {
  taskId: number;
  title: string;
  status: TaskStatus;
  runtimeCode: string;
  modelProfileId?: string;
  modelName?: string;
  modelIdentifier?: string;
  modelTimingNote?: string;
  startedAt: string;
  endedAt: string;
  totalDurationMs: number;
  modelApiWaitDurationMs: number;
  modelStreamingDurationMs: number;
  modelDecisionDurationMs: number;
  modelGenerationDurationMs: number;
  modelProcessingDurationMs: number;
  orchestrationDurationMs: number;
  commandWallDurationMs: number;
  commandExecutionDurationMs: number;
  resultProcessingMs: number;
  firstFeedbackMs?: number;
  requestCount: number;
  modelRoundCount: number;
  toolCallCount: number;
  inputTokens: number;
  cachedInputTokens: number;
  cacheCreationInputTokens: number;
  outputTokens: number;
  reasoningOutputTokens: number;
  totalTokens: number;
  estimatedInputTokens: number;
  systemInstructionTokens: number;
  taskInstructionTokens: number;
  mcpInstructionTokens: number;
  toolSchemaTokens: number;
  conversationTokens: number;
  toolResultTokens: number;
  imageTokens: number;
  costCurrency?: string;
  costAmount?: number;
  cacheHitInputCost?: number;
  cacheMissInputCost?: number;
  outputCost?: number;
  priceTier?: string;
  compactionCount: number;
  duplicateCapabilityCallCount: number;
  primaryFinding?: string;
  primaryFindingDetail?: string;
  steps: TaskExecutionReportStep[];
  modelCalls: TaskExecutionReportModelCall[];
}

export interface TaskItem {
  id: number;
  ownerUserId?: number;
  ownerUsername?: string;
  ownerDisplayName?: string;
  scenarioCode?: string;
  premiseId?: number;
  premiseName?: string;
  premiseSnapshotName?: string;
  premiseSnapshotDescription?: string;
  premiseSnapshotContextValues?: Record<string, string>;
  scenarioName?: string;
  scenarioIconUrl?: string;
  scenarioColor?: string;
  resultRenderer?: string;
  recommendedScenarioCodes?: string[];
  scenario: TaskScenario;
  runtimeCode: string;
  modelProfileId?: string;
  modelProviderId?: string;
  modelProviderName?: string;
  modelName?: string;
  modelIdentifier?: string;
  modelProtocol?: RuntimeModelProtocol;
  modelContextWindowTokens?: number;
  modelReasoningEffort?: string;
  status: TaskStatus;
  title: string;
  userInput: string;
  inputValues?: TaskInputValue[];
  attachments?: TaskAttachment[];
  prompt?: string;
  stdoutText?: string;
  stderrText?: string;
  resultText?: string;
  resultData?: StructuredTaskResult;
  events?: TaskEventItem[];
  liveAgentMessages?: Record<string, TaskLiveMessage>;
  eventEntries?: TaskEventEntry[];
  interactions?: TaskInteractionItem[];
  roundCount?: number;
  roundSummaries?: TaskRoundSummary[];
  exitCode?: number;
  sourceTaskId?: number;
  conversationRootTaskId?: number;
  roundNo?: number;
  requestCount?: number;
  inputTokens?: number;
  cachedInputTokens?: number;
  cacheCreationInputTokens?: number;
  outputTokens?: number;
  reasoningOutputTokens?: number;
  totalTokens?: number;
  resourceMemoryMetrics?: ResourceMemoryMetrics;
  executionMetrics?: TaskExecutionMetrics;
  startedAt?: string;
  endedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface TaskRoundSummary {
  id: number;
  runtimeCode?: string;
  modelProfileId?: string;
  modelProviderId?: string;
  modelProviderName?: string;
  modelName?: string;
  modelIdentifier?: string;
  modelProtocol?: RuntimeModelProtocol;
  modelReasoningEffort?: string;
  roundNo?: number;
  userInput?: string;
  status: TaskStatus;
  requestCount?: number;
  inputTokens?: number;
  cachedInputTokens?: number;
  cacheCreationInputTokens?: number;
  outputTokens?: number;
  reasoningOutputTokens?: number;
  totalTokens?: number;
  resourceMemoryMetrics?: ResourceMemoryMetrics;
  executionMetrics?: TaskExecutionMetrics;
  startedAt?: string;
  endedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface TaskUsageSnapshot {
  requestCount?: number;
  inputTokens?: number;
  cachedInputTokens?: number;
  cacheCreationInputTokens?: number;
  outputTokens?: number;
  reasoningOutputTokens?: number;
  totalTokens?: number;
}

export interface TaskMessageDelta {
  messageId: string;
  delta?: string;
  content?: string;
  type?: TaskLiveMessageType;
}

export type TaskLiveMessageType = 'MESSAGE' | 'REASONING';

export interface TaskLiveMessage {
  type: TaskLiveMessageType;
  content: string;
}

export interface TaskShareCreateResult {
  shareCode: string;
  password: string;
}

export interface TaskShareCreateRequest {
  roundTaskIds: number[];
}

export interface TaskSharePreviewRequest {
  password: string;
}

export interface TaskSharePreview {
  shareCode: string;
  taskId: number;
  sharedByUsername?: string;
  sharedByDisplayName?: string;
  scenarioName?: string;
  scenario: TaskScenario;
  resultRenderer?: string;
  status: TaskStatus;
  title: string;
  userInput: string;
  sharedRoundNo?: number;
  sharedRoundUserInput?: string;
  resultText?: string;
  resultData?: StructuredTaskResult;
  rounds?: TaskShareRoundPreview[];
  startedAt?: string;
  endedAt?: string;
  createdAt: string;
}

export interface TaskShareRoundPreview {
  taskId: number;
  roundNo: number;
  userInput: string;
  resultRenderer?: string;
  resultText?: string;
  resultData?: StructuredTaskResult;
  startedAt?: string;
  endedAt?: string;
  createdAt: string;
}

export interface TaskEventItem {
  id: number;
  type: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'INFO';
  title: string;
  detail?: string;
  detailFile?: boolean;
  payload?: TaskEventPayload;
  createdAt: string;
}

export interface TaskEventEntry {
  type: 'status' | 'message';
  text: string;
  state?: 'running' | 'done' | 'failed' | 'warning' | 'thinking' | 'reasoning' | 'system';
  count?: number;
  details?: string[];
}

export interface TaskEventPayload {
  rawType?: string;
  semantic?: 'FINAL_ANSWER' | 'MODEL_REQUEST_STARTED' | 'MODEL_REQUEST_FIRST_RESPONSE' | 'MODEL_REQUEST_COMPLETED' | 'MODEL_REQUEST_FAILED' | 'CONTEXT_COMPACTION';
  visibility?: 'PUBLIC' | 'INTERNAL';
  modelTimingMode?: 'STREAMING' | 'OBSERVED';
  actionIcon?: RuntimeActionIcon;
  itemType?: string;
  itemId?: string;
  status?: string;
  command?: string;
  toolName?: string;
  callId?: string;
  arguments?: string;
  output?: string;
  message?: string;
  exitCode?: number;
  actionKey?: string;
  actionInstanceId?: string;
  actionLabel?: string;
  actionTarget?: string;
  detailFile?: boolean;
  argumentsFile?: boolean;
  outputFile?: boolean;
  messageFile?: boolean;
  commandFile?: boolean;
  actionTargetFile?: boolean;
  transientEvent?: boolean;
  metrics?: Record<string, string>;
  actionGroupId?: string;
  actionGroupSize?: number;
  interactionId?: number;
  interactionStatus?: TaskInteractionStatus;
  interactionInputType?: TaskInteractionInputType;
  question?: string;
  interactionContent?: string;
  options?: TaskInteractionOption[];
  actions?: TaskInteractionAction[];
  fields?: TaskInteractionField[];
  required?: boolean;
  placeholder?: string;
  answerHint?: string;
  defaultValue?: string;
  contextKey?: string;
  answerText?: string;
  selectedValues?: string[];
  answerValues?: TaskInteractionAnswerValue[];
  interactionAnswerAction?: string;
  interactionAnswerActionKey?: string;
}

export type RuntimeActionIcon = 'WRENCH' | 'TERMINAL' | 'BOOK_OPEN' | 'FILE_TEXT' | 'FILE_PLUS'
  | 'FILE_MAGNIFYING_GLASS' | 'MAGNIFYING_GLASS' | 'BRACKETS_CURLY' | 'CODE' | 'GRAPH'
  | 'GIT_DIFF' | 'TREE_STRUCTURE' | 'PLUGS_CONNECTED' | 'QUESTION' | 'LIST_BULLETS'
  | 'CHECK_CIRCLE' | 'CALENDAR' | 'CLOCK' | 'DATABASE' | 'FOLDER' | 'GIT_BRANCH'
  | 'GIT_COMMIT' | 'SHIELD_CHECK' | 'ARROW_COUNTER_CLOCKWISE' | 'PLAY_CIRCLE'
  | 'VIDEO_CAMERA' | 'IMAGE_SQUARE' | 'EYE' | 'HISTORY' | 'BROWSER' | 'HEAD_CIRCUIT'
  | 'WARNING_CIRCLE' | 'X_CIRCLE';

export type TaskInteractionInputType = 'TEXT' | 'TEXTAREA' | 'YES_NO' | 'DATE' | 'DATETIME' | 'SELECT' | 'SINGLE_CHOICE' | 'MULTI_CHOICE' | 'CONFIRM' | 'FORM';
export type TaskInteractionStatus = 'PENDING' | 'ANSWERED' | 'SKIPPED' | 'UNKNOWN' | 'CANCELED' | 'EXPIRED';
export type TaskInteractionAnswerAction = 'SUBMIT' | 'SKIP' | 'UNKNOWN' | 'CANCEL' | string;

export interface TaskInteractionOption {
  label: string;
  value: string;
  description?: string;
}

export interface TaskInteractionAction {
  key: string;
  label: string;
  description?: string;
  style?: 'primary' | 'danger' | 'default' | string;
  validateInput?: boolean;
}

export interface TaskInteractionField {
  key: string;
  label: string;
  type: TaskInteractionInputType;
  options?: TaskInteractionOption[];
  required?: boolean;
  placeholder?: string;
  defaultValue?: string;
  description?: string;
  contextKey?: string;
}

export interface TaskInteractionAnswerValue {
  key: string;
  value?: string;
  selectedValues?: string[];
}

export interface TaskInteractionItem {
  id: number;
  taskId: number;
  question: string;
  content?: string;
  inputType: TaskInteractionInputType;
  options?: TaskInteractionOption[];
  actions?: TaskInteractionAction[];
  fields?: TaskInteractionField[];
  required?: boolean;
  placeholder?: string;
  answerHint?: string;
  defaultValue?: string;
  contextKey?: string;
  status: TaskInteractionStatus;
  answerText?: string;
  selectedValues?: string[];
  answerValues?: TaskInteractionAnswerValue[];
  answerAction?: string;
  answerActionKey?: string;
  createdAt: string;
}

export interface TaskInteractionAnswerRequest {
  action?: TaskInteractionAnswerAction;
  answerText?: string;
  selectedValues?: string[];
  answerValues?: TaskInteractionAnswerValue[];
}

export interface TaskEventContentResponse {
  content: string;
}

export interface StructuredTaskResult {
  format: string;
  renderer?: string;
  markdown?: string;
  recommendedScenarioCodes?: string[];
  sections?: Array<{
    title: string;
    kind: string;
    content: string;
  }>;
}

export interface CreateTaskRequest {
  runtimeCode: string;
  modelProfileId: string;
  scenarioCode: string;
  premiseId?: number;
  userInput: string;
  inputValues?: TaskInputValue[];
  attachmentIds?: string[];
  sourceTaskId?: number;
}

export interface TaskAttachment {
  id: string;
  name: string;
  contentType: string;
  size: number;
  url: string;
  inputKind?: string;
}

export interface TaskInputValue {
  key: string;
  value?: string;
}

export interface RuntimeMaintenanceOperation {
  status: CliInstallStatus;
  osType?: RuntimeOs;
  startedAt?: string;
  updatedAt?: string;
  endedAt?: string;
  exitCode: number;
  stdoutText: string;
  stderrText: string;
}

export interface DashboardTokenDimension {
  dimensionKey: string;
  dimensionName: string;
  ownerUserId?: number;
  ownerUsername?: string;
  ownerDisplayName?: string;
  scenario?: TaskScenario;
  scenarioCode?: string;
  scenarioName?: string;
  scenarioColor?: string;
  modelName?: string;
  modelIdentifier?: string;
  taskCount: number;
  requestCount: number;
  pendingCount: number;
  runningCount: number;
  waitingUserCount: number;
  successCount: number;
  failedCount: number;
  canceledCount: number;
  inputTokens: number;
  cachedInputTokens: number;
  cacheCreationInputTokens: number;
  outputTokens: number;
  reasoningOutputTokens: number;
  totalTokens: number;
  averageTokens: number;
}

export type DashboardTimeGranularity = 'day' | 'week' | 'month';

export interface DashboardTokenUsage {
  summary: DashboardTokenDimension;
  resourceMemory: ResourceMemoryMetrics;
  executionExperience: DashboardExecutionMetrics;
  byOwners: DashboardTokenDimension[];
  byQuestionTypes: DashboardTokenDimension[];
  byModels: DashboardTokenDimension[];
  trends: DashboardTokenTrend[];
}

export interface DashboardExecutionMetrics {
  measuredTaskCount: number;
  averageTotalDurationMs: number;
  averageFirstFeedbackMs: number;
  averageCommandDurationMs: number;
  averageResultProcessingMs: number;
  compactionCount: number;
  duplicateCapabilityCallCount: number;
}

export interface DashboardTokenTrend {
  date: string;
  taskCount: number;
  requestCount: number;
  inputTokens: number;
  cachedInputTokens: number;
  cacheCreationInputTokens: number;
  outputTokens: number;
  reasoningOutputTokens: number;
  totalTokens: number;
  averageTokens: number;
}
