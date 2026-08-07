export interface Result<T> {
  code: string;
  subcode?: string;
  message?: string;
  data: T;
}

export interface Environment {
  id?: number;
  projectId?: number;
  code: string;
  name: string;
  deploymentBranch?: string;
  repositories: Repository[];
  databases: DatabaseConfig[];
}

export interface Repository {
  id?: number;
  projectId?: number;
  environmentId?: number;
  environmentCode?: string;
  environmentName?: string;
  code: string;
  name: string;
  description?: string;
  repositoryUrl: string;
  baseBranch: string;
  primaryRepo: boolean;
}

export interface DatabaseConfig {
  id?: number;
  projectId?: number;
  environmentId?: number;
  environmentCode?: string;
  environmentName?: string;
  code: string;
  name: string;
  url: string;
  username?: string;
  password?: string;
  schemaHint?: string;
}

export interface Relation {
  id?: number;
  projectId?: number;
  relatedProjectId: number;
  relatedProjectCode?: string;
  relatedProjectName?: string;
  direction: string;
  relationType: string;
  name?: string;
  description?: string;
}

export interface Project {
  id: number;
  code: string;
  name: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
  environments: Environment[];
  repositories: Repository[];
  relations: Relation[];
}

export interface ProjectInput {
  code: string;
  name: string;
  description?: string;
  environments: Environment[];
  relations: Relation[];
}

export interface MarkdownDocument {
  id: number;
  projectCode: string;
  title: string;
  path: string;
  category?: string;
  content: string;
  revision: number;
  createdAt: string;
  updatedAt: string;
}

export type DocumentInput = Pick<MarkdownDocument, 'projectCode' | 'title' | 'path' | 'category' | 'content'>;

export interface LogEntry {
  id: number;
  projectId: number;
  projectCode: string;
  environmentCode: string;
  serviceName: string;
  occurredAt: string;
  level: string;
  traceId?: string;
  content: string;
}

export interface LogInput {
  projectId: number;
  environmentCode: string;
  serviceName: string;
  occurredAt?: string;
  level?: string;
  traceId?: string;
  content: string;
}

export interface DemoInfo {
  baseUrl: string;
  token: string;
  projectHubBaseUrl: string;
  wikiBaseUrl: string;
  httpLogBaseUrl: string;
}

export interface BundleCapability {
  code: string;
  name: string;
  description: string;
  packageUrl: string;
  iconUrl?: string;
}

export interface BundleScenario {
  code: string;
  name: string;
  version: string;
  description?: string;
  slogan?: string;
  color?: string;
  iconUrl?: string;
  packageUrl: string;
  capabilities: string[];
}

export interface BundleConfiguration {
  capabilityCode: string;
  name: string;
  description?: string;
  config: Record<string, string | number | boolean | null>;
}

export interface BundleCatalog {
  schemaVersion: number;
  name: string;
  version: string;
  description?: string;
  capabilities: BundleCapability[];
  configurations: BundleConfiguration[];
  scenarios: BundleScenario[];
}
