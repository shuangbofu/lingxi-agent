import request from './request';
import type {
  BundleCatalog,
  DemoInfo,
  DocumentInput,
  LogEntry,
  LogInput,
  MarkdownDocument,
  Project,
  ProjectInput,
} from '../types';

export const demoApi = {
  info: () => request.get<never, DemoInfo>('/admin/info'),
  catalog: (baseUrl: string) => request.get<never, BundleCatalog>(`${baseUrl.replace(/\/+$/, '')}/setup/catalog`),
  projects: () => request.get<never, Project[]>('/admin/projects'),
  project: (id: number) => request.get<never, Project>(`/admin/projects/${id}`),
  createProject: (input: ProjectInput) => request.post<never, Project>('/admin/projects', input),
  updateProject: (id: number, input: ProjectInput) => request.put<never, Project>(`/admin/projects/${id}`, input),
  deleteProject: (id: number) => request.delete<never, boolean>(`/admin/projects/${id}`),
  documents: (projectCode?: string) => request.get<never, MarkdownDocument[]>('/admin/documents', { params: { projectCode } }),
  createDocument: (input: DocumentInput) => request.post<never, MarkdownDocument>('/admin/documents', input),
  updateDocument: (id: number, input: DocumentInput) => request.put<never, MarkdownDocument>(`/admin/documents/${id}`, input),
  deleteDocument: (id: number) => request.delete<never, boolean>(`/admin/documents/${id}`),
  logs: (projectId?: number, environmentCode?: string) =>
    request.get<never, LogEntry[]>('/admin/logs', { params: { projectId, environmentCode } }),
  createLog: (input: LogInput) => request.post<never, LogEntry>('/admin/logs', input),
  createLogBatch: (input: LogInput) => request.post<never, LogEntry[]>('/admin/logs/batch', input),
  deleteLog: (id: number) => request.delete<never, boolean>(`/admin/logs/${id}`),
};
