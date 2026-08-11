import request from './request';
import type {
  CreateTaskRequest,
  RuntimeMaintenanceStatus,
  RuntimeMaintenanceOperation,
  DashboardTimeGranularity,
  DashboardTokenUsage,
  AnalysisPremiseItem,
  AnalysisPremiseContextParameter,
  AnalysisPremiseOption,
  AnalysisPremiseSaveRequest,
  CapabilityConfigItem,
  CapabilityConfigSaveRequest,
  CapabilityPackageInspection,
  AgentCapability,
  AgentCapabilityStateUpdateRequest,
  AgentScenario,
  AgentScenarioStateUpdateRequest,
  ScenarioPackageInspection,
  CurrentUser,
  InitialAdminSetupRequest,
  LoginRequest,
  PageResult,
  PasswordChangeRequest,
  Profile,
  PublicLoginResponse,
  PublicScenario,
  RuntimeConfig,
  ModelEndpointCheckRequest,
  ModelEndpointCheckResult,
  RuntimeConfigRequest,
  AgentRuntimeDescriptor,
  ModelCatalog,
  ModelConfigScope,
  RuntimeModelProfile,
  RuntimeModelProvider,
  RuntimePricingPlan,
  McpServer,
  McpServerRequest,
  TaskItem,
  TaskExecutionReport,
  TaskAttachment,
  TaskRoundSummary,
  TaskEventContentResponse,
  TaskInteractionAnswerRequest,
  TaskInteractionItem,
  TaskScenario,
  TaskShareCreateResult,
  TaskShareCreateRequest,
  TaskSharePreview,
  TaskSharePreviewRequest,
  TaskStatus,
  UserItem,
  UserUsageSettingsRequest,
  UserSaveRequest,
  UserUsageQuota,
  UserAvatarUploadResult,
} from '../types/api';

export interface TaskPageQuery {
  page: number;
  size: number;
  scenario?: TaskScenario;
  status?: TaskStatus;
  recordType?: string;
  scope?: 'mine';
  query?: string;
  ownerId?: number;
  createdStart?: string;
  createdEnd?: string;
  runtimeCode?: string;
  modelProfileId?: string;
  sortBy?: 'createdAt' | 'updatedAt';
  aggregateConversation?: boolean;
}

export function getCurrentUser() {
  return request.get<CurrentUser, CurrentUser>('/api/auth/me');
}

export function login(data: LoginRequest) {
  return request.post<CurrentUser, CurrentUser>('/api/auth/login', data);
}

export function setupInitialAdmin(data: InitialAdminSetupRequest) {
  return request.post<CurrentUser, CurrentUser>('/api/auth/setup', data);
}

export function logout() {
  return request.post<void, void>('/api/auth/logout');
}

export function listPublicScenarios() {
  return request.get<PublicScenario[], PublicScenario[]>('/api/public/scenarios', {
    publicRequest: true,
  });
}

export function getPublicLoginConfig() {
  return request.get<PublicLoginResponse, PublicLoginResponse>('/api/public/login', {
    publicRequest: true,
  });
}

export function changePassword(data: PasswordChangeRequest) {
  return request.post<void, void>('/api/auth/password', data);
}

export function getProfile() {
  return request.get<Profile, Profile>('/api/profile');
}

export function getProfileUsage(params?: { createdStart?: string; createdEnd?: string }) {
  return request.get<DashboardTokenUsage, DashboardTokenUsage>('/api/profile/usage', { params });
}

export function listUsers() {
  return request.get<UserItem[], UserItem[]>('/api/users');
}

export function pageUsers(page: number, size: number) {
  return request.get<PageResult<UserItem>, PageResult<UserItem>>('/api/users/page', {
    params: { page, size },
  });
}

export function createUser(data: UserSaveRequest) {
  return request.post<UserItem, UserItem>('/api/users', data);
}

export function updateUser(id: number, data: UserSaveRequest) {
  return request.put<UserItem, UserItem>(`/api/users/${id}`, data);
}

export function updateUserUsageSettings(id: number, data: UserUsageSettingsRequest) {
  return request.put<UserItem, UserItem>(`/api/users/${id}/usage-settings`, data);
}

export function getUserUsageQuota(id: number) {
  return request.get<UserUsageQuota, UserUsageQuota>(`/api/users/${id}/usage-quota`);
}

export function uploadUserAvatar(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return request.post<UserAvatarUploadResult, UserAvatarUploadResult>('/api/users/avatar', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function listAvailableAnalysisPremises() {
  return request.get<AnalysisPremiseOption[], AnalysisPremiseOption[]>('/api/analysis-premises/available');
}

export function pageAnalysisPremises(page: number, size: number, query?: string) {
  return request.get<PageResult<AnalysisPremiseItem>, PageResult<AnalysisPremiseItem>>('/api/analysis-premises/page', {
    params: { page, size, query },
  });
}

export function listAnalysisPremiseContextParameters() {
  return request.get<AnalysisPremiseContextParameter[], AnalysisPremiseContextParameter[]>('/api/analysis-premises/context-parameters');
}

export function createAnalysisPremise(data: AnalysisPremiseSaveRequest) {
  return request.post<AnalysisPremiseItem, AnalysisPremiseItem>('/api/analysis-premises', data);
}

export function updateAnalysisPremise(id: number, data: AnalysisPremiseSaveRequest) {
  return request.put<AnalysisPremiseItem, AnalysisPremiseItem>(`/api/analysis-premises/${id}`, data);
}

export function deleteAnalysisPremise(id: number) {
  return request.delete<void, void>(`/api/analysis-premises/${id}`);
}

export function listEnabledScenarios(premiseId?: number) {
  return request.get<AgentScenario[], AgentScenario[]>('/api/scenarios', {
    params: { premiseId },
  });
}

export function listUserVisibleScenarios() {
  return request.get<AgentScenario[], AgentScenario[]>('/api/scenarios', {
    params: { includeUnavailable: true },
  });
}

export function listAllScenarios() {
  return request.get<AgentScenario[], AgentScenario[]>('/api/scenarios/admin');
}

export function pageAllScenarios(page: number, size: number) {
  return request.get<PageResult<AgentScenario>, PageResult<AgentScenario>>('/api/scenarios/admin/page', {
    params: { page, size },
  });
}

export function getScenario(code: string) {
  return request.get<AgentScenario, AgentScenario>(`/api/scenarios/${code}`);
}

export function inspectScenarioPackage(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return request.post<ScenarioPackageInspection, ScenarioPackageInspection>('/api/scenarios/packages/inspect', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function installScenarioPackage(stagingToken: string) {
  return request.post<AgentScenario, AgentScenario>(`/api/scenarios/packages/${stagingToken}/install`);
}

export function updateScenario(code: string, data: AgentScenarioStateUpdateRequest) {
  return request.put<AgentScenario, AgentScenario>(`/api/scenarios/${code}`, data);
}

export function uninstallScenario(code: string) {
  return request.delete<void, void>(`/api/scenarios/${code}`);
}

export function reorderScenarios(codes: string[]) {
  return request.put<AgentScenario[], AgentScenario[]>('/api/scenarios/order', { codes });
}

export function listAllCapabilities() {
  return request.get<AgentCapability[], AgentCapability[]>('/api/capabilities/admin');
}

export function pageAllCapabilities(page: number, size: number) {
  return request.get<PageResult<AgentCapability>, PageResult<AgentCapability>>('/api/capabilities/admin/page', {
    params: { page, size },
  });
}

export function getCapability(code: string) {
  return request.get<AgentCapability, AgentCapability>(`/api/capabilities/${code}`);
}

export function updateCapability(code: string, data: AgentCapabilityStateUpdateRequest) {
  return request.put<AgentCapability, AgentCapability>(`/api/capabilities/${code}`, data);
}

export function uninstallCapability(code: string) {
  return request.delete<void, void>(`/api/capabilities/${code}`);
}

export function inspectCapabilityPackage(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return request.post<CapabilityPackageInspection, CapabilityPackageInspection>('/api/capabilities/packages/inspect', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function installCapabilityPackage(stagingToken: string) {
  return request.post<AgentCapability, AgentCapability>(`/api/capabilities/packages/${stagingToken}/install`);
}

export function pageCapabilityConfigs(page: number, size: number, capabilityCode?: string) {
  return request.get<PageResult<CapabilityConfigItem>, PageResult<CapabilityConfigItem>>('/api/capability-configs/page', {
    params: { page, size, capabilityCode },
  });
}

export function createCapabilityConfig(data: CapabilityConfigSaveRequest) {
  return request.post<CapabilityConfigItem, CapabilityConfigItem>('/api/capability-configs', data);
}

export function updateCapabilityConfig(id: number, data: CapabilityConfigSaveRequest) {
  return request.put<CapabilityConfigItem, CapabilityConfigItem>(`/api/capability-configs/${id}`, data);
}

export function deleteCapabilityConfig(id: number) {
  return request.delete<void, void>(`/api/capability-configs/${id}`);
}

export function getRuntimeMaintenanceStatus(runtimeCode: string) {
  return request.get<RuntimeMaintenanceStatus, RuntimeMaintenanceStatus>(`/api/runtime/${runtimeCode}/maintenance/status`);
}

export function installRuntime(runtimeCode: string) {
  return request.post<RuntimeMaintenanceOperation, RuntimeMaintenanceOperation>(`/api/runtime/${runtimeCode}/maintenance/install`);
}

export function getRuntimeInstallStatus(runtimeCode: string) {
  return request.get<RuntimeMaintenanceOperation, RuntimeMaintenanceOperation>(`/api/runtime/${runtimeCode}/maintenance/install/status`);
}

export function getRuntimeConfig() {
  return request.get<RuntimeConfig, RuntimeConfig>('/api/runtime/config');
}

export function saveRuntimeConfig(data: RuntimeConfigRequest) {
  return request.put<RuntimeConfig, RuntimeConfig>('/api/runtime/config', data);
}

export function getMcpServers() {
  return request.get<McpServer[], McpServer[]>('/api/mcp-servers');
}

export function pageMcpServers(page: number, size: number) {
  return request.get<PageResult<McpServer>, PageResult<McpServer>>('/api/mcp-servers/page', {
    params: { page, size },
  });
}

export function createMcpServer(data: McpServerRequest) {
  return request.post<McpServer, McpServer>('/api/mcp-servers', data);
}

export function updateMcpServer(id: string, data: McpServerRequest) {
  return request.put<McpServer, McpServer>(`/api/mcp-servers/${id}`, data);
}

export function deleteMcpServer(id: string) {
  return request.delete<void, void>(`/api/mcp-servers/${id}`);
}

export function checkModelEndpoint(data: ModelEndpointCheckRequest) {
  return request.post<ModelEndpointCheckResult, ModelEndpointCheckResult>('/api/model/check', data);
}

export function getModelCatalog(scope: ModelConfigScope) {
  return request.get<ModelCatalog, ModelCatalog>('/api/model-configs', { params: { scope } });
}

export function createModelProvider(scope: ModelConfigScope, data: RuntimeModelProvider) {
  return request.post<RuntimeModelProvider, RuntimeModelProvider>('/api/model-configs/providers', data, { params: { scope } });
}

export function updateModelProvider(scope: ModelConfigScope, id: string, data: RuntimeModelProvider) {
  return request.put<RuntimeModelProvider, RuntimeModelProvider>(`/api/model-configs/providers/${id}`, data, { params: { scope } });
}

export function deleteModelProvider(scope: ModelConfigScope, id: string) {
  return request.delete<void, void>(`/api/model-configs/providers/${id}`, { params: { scope } });
}

export function createModelProfile(scope: ModelConfigScope, data: RuntimeModelProfile) {
  return request.post<RuntimeModelProfile, RuntimeModelProfile>('/api/model-configs/models', data, { params: { scope } });
}

export function updateModelProfile(scope: ModelConfigScope, id: string, data: RuntimeModelProfile) {
  return request.put<RuntimeModelProfile, RuntimeModelProfile>(`/api/model-configs/models/${id}`, data, { params: { scope } });
}

export function deleteModelProfile(scope: ModelConfigScope, id: string) {
  return request.delete<void, void>(`/api/model-configs/models/${id}`, { params: { scope } });
}

export function createPricingPlan(scope: ModelConfigScope, data: RuntimePricingPlan) {
  return request.post<RuntimePricingPlan, RuntimePricingPlan>('/api/model-configs/pricing-plans', data, { params: { scope } });
}

export function updatePricingPlan(scope: ModelConfigScope, id: string, data: RuntimePricingPlan) {
  return request.put<RuntimePricingPlan, RuntimePricingPlan>(`/api/model-configs/pricing-plans/${id}`, data, { params: { scope } });
}

export function deletePricingPlan(scope: ModelConfigScope, id: string) {
  return request.delete<void, void>(`/api/model-configs/pricing-plans/${id}`, { params: { scope } });
}

export function getAgentRuntimes() {
  return request.get<AgentRuntimeDescriptor[], AgentRuntimeDescriptor[]>('/api/runtime');
}

export function getDashboardTokenUsage(params: {
  createdStart?: string;
  createdEnd?: string;
  ownerId?: number;
  scenario?: TaskScenario;
  modelProfileId?: string;
  runtimeCode?: string;
  granularity?: DashboardTimeGranularity;
}) {
  return request.get<DashboardTokenUsage, DashboardTokenUsage>('/api/dashboard/token-usage', {
    params,
  });
}

export function pageTasks(params: TaskPageQuery) {
  return request.get<PageResult<TaskItem>, PageResult<TaskItem>>('/api/agent/tasks', {
    params,
  });
}

export function listTaskOwners() {
  return request.get<UserItem[], UserItem[]>('/api/agent/tasks/owners');
}

export function createTask(data: CreateTaskRequest) {
  return request.post<TaskItem, TaskItem>('/api/agent/tasks', data);
}

export function rerunTask(id: number, runtimeCode: string, modelProfileId: string) {
  return request.post<TaskItem, TaskItem>(`/api/agent/tasks/${id}/rerun`, { runtimeCode, modelProfileId });
}

export function uploadTaskAttachment(file: File, inputKind?: string) {
  const formData = new FormData();
  formData.append('file', file);
  if (inputKind) {
    formData.append('inputKind', inputKind);
  }
  return request.post<TaskAttachment, TaskAttachment>('/api/agent/task-attachments', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  });
}

export function getTaskAttachmentText(url: string) {
  return request.get<string, string>(url, { responseType: 'text' });
}

export function getTask(id: number, options?: { includeEvents?: boolean }) {
  return request.get<TaskItem, TaskItem>(`/api/agent/tasks/${id}`, {
    params: options?.includeEvents ? { includeEvents: true } : undefined,
  });
}

export function getTaskExecutionReport(id: number) {
  return request.get<TaskExecutionReport, TaskExecutionReport>(`/api/agent/tasks/${id}/execution-report`);
}

export function getTaskArtifact(taskId: number, path: string) {
  return request.get<ArrayBuffer, ArrayBuffer>(`/api/agent/tasks/${taskId}/artifacts`, {
    params: { path },
    responseType: 'arraybuffer',
  });
}

export function listTaskRounds(id: number) {
  return request.get<TaskRoundSummary[], TaskRoundSummary[]>(`/api/agent/tasks/${id}/rounds`);
}

export function continueTaskRound(id: number, data: { userInput: string; attachmentIds?: string[] }) {
  return request.post<TaskItem, TaskItem>(`/api/agent/tasks/${id}/rounds`, data);
}

export function cancelTask(id: number) {
  return request.post<TaskItem, TaskItem>(`/api/agent/tasks/${id}/cancel`);
}

export function retryTask(id: number) {
  return request.post<TaskItem, TaskItem>(`/api/agent/tasks/${id}/retry`);
}

export function resumeTask(id: number, data?: { userInput?: string }) {
  return request.post<TaskItem, TaskItem>(`/api/agent/tasks/${id}/resume`, data);
}

export function listTaskInteractions(id: number) {
  return request.get<TaskInteractionItem[], TaskInteractionItem[]>(`/api/agent/tasks/${id}/interactions`);
}

export function answerTaskInteraction(taskId: number, interactionId: number, data: TaskInteractionAnswerRequest) {
  return request.post<TaskInteractionItem, TaskInteractionItem>(`/api/agent/tasks/${taskId}/interactions/${interactionId}/answer`, data);
}

export function createTaskShare(id: number, data: TaskShareCreateRequest) {
  return request.post<TaskShareCreateResult, TaskShareCreateResult>(`/api/agent/tasks/${id}/share`, data);
}

export function previewTaskShare(shareCode: string, data: TaskSharePreviewRequest) {
  return request.post<TaskSharePreview, TaskSharePreview>(`/api/public/task-shares/${shareCode}/preview`, data, {
    publicRequest: true,
  });
}

export function getTaskShareMeta(shareCode: string) {
  return request.get<TaskSharePreview, TaskSharePreview>(`/api/public/task-shares/${shareCode}/meta`, {
    publicRequest: true,
  });
}

export function getTaskEventContent(taskId: number, eventId: number, field: string) {
  return request.get<TaskEventContentResponse, TaskEventContentResponse>(`/api/agent/tasks/${taskId}/events/${eventId}/content`, {
    params: { field },
  });
}
