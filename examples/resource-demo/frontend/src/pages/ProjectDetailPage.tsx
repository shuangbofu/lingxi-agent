import {
  ApartmentOutlined,
  ArrowLeftOutlined,
  BranchesOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  InfoCircleOutlined,
  PlusOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { App, Button, Checkbox, Empty, Form, Input, Popconfirm, Select, Space, Spin, Tabs, Tooltip } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { demoApi } from '../api/demo';
import { PageHeading } from '../components/PageHeading';
import type { Environment, Project, ProjectInput } from '../types';

function createEnvironment(): Environment {
  return {
    code: 'main',
    name: '开源主线',
    deploymentBranch: 'main',
    repositories: [],
    databases: [],
  };
}

export function ProjectDetailPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { projectId: projectIdValue } = useParams();
  const projectId = projectIdValue && projectIdValue !== 'new' ? Number(projectIdValue) : undefined;
  const [form] = Form.useForm<ProjectInput>();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [activeTab, setActiveTab] = useState('overview');
  const [activeEnvironment, setActiveEnvironment] = useState('0');
  const environments = Form.useWatch('environments', form) || [];
  const relations = Form.useWatch('relations', form) || [];

  useEffect(() => {
    let active = true;
    setLoading(true);
    void Promise.all([
      demoApi.projects(),
      projectId ? demoApi.project(projectId) : Promise.resolve(undefined),
    ]).then(([items, detail]) => {
      if (!active) {
        return;
      }
      setProjects(items);
      form.setFieldsValue(detail ? {
        code: detail.code,
        name: detail.name,
        description: detail.description,
        environments: detail.environments,
        relations: detail.relations,
      } : {
        code: '',
        name: '',
        description: '',
        environments: [createEnvironment()],
        relations: [],
      });
      setLoading(false);
    }).catch(() => {
      if (active) {
        setLoading(false);
      }
    });
    return () => {
      active = false;
    };
  }, [form, projectId]);

  const relationOptions = useMemo(() => projects
    .filter((item) => item.id !== projectId)
    .map((item) => ({ label: item.name, value: item.id })), [projectId, projects]);

  const saveProject = async () => {
    const values = await form.validateFields();
    if (!values.environments.length) {
      message.error('至少配置一个环境');
      setActiveTab('environments');
      return;
    }
    const environmentCodes = values.environments.map((item) => item.code.toLowerCase());
    if (new Set(environmentCodes).size !== environmentCodes.length) {
      message.error('同一项目下的环境编码不能重复');
      setActiveTab('environments');
      return;
    }
    for (const environment of values.environments) {
      const repositoryCodes = environment.repositories.map((item) => item.code.toLowerCase());
      const databaseCodes = environment.databases.map((item) => item.code.toLowerCase());
      if (new Set(repositoryCodes).size !== repositoryCodes.length
          || new Set(databaseCodes).size !== databaseCodes.length) {
        message.error(`${environment.name}中存在重复的资源编码`);
        setActiveTab('environments');
        return;
      }
    }
    setSaving(true);
    try {
      if (projectId) {
        await demoApi.updateProject(projectId, values);
      } else {
        await demoApi.createProject(values);
      }
      message.success('项目已保存');
      navigate('/projects');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="detail-loading"><Spin /><span>正在读取项目</span></div>;
  }

  return (
    <div className="project-detail-page">
      <PageHeading
        title={projectId ? '编辑项目' : '新增项目'}
        description={projectId ? form.getFieldValue('name') || '维护项目上下文和环境资源' : '维护项目上下文和环境资源'}
        actions={(
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/projects')}>返回</Button>
            <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={() => void saveProject()}>保存</Button>
          </Space>
        )}
      />

      <Form form={form} layout="vertical">
        <Tabs
          className="project-detail-tabs"
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'overview',
              label: <TabLabel icon={<InfoCircleOutlined />} text="基本信息" />,
              children: (
                <section className="project-tab-panel">
                  <div className="project-basic-grid">
                    <Form.Item name="name" label="项目名称" rules={[{ required: true, message: '请填写项目名称' }]}>
                      <Input placeholder="例如：OpenAI Codex" />
                    </Form.Item>
                    <Form.Item name="code" label="项目编码" rules={[{ required: true, message: '请填写项目编码' }]}>
                      <Input placeholder="例如：openai-codex" disabled={Boolean(projectId)} />
                    </Form.Item>
                  </div>
                  <Form.Item name="description" label="项目说明"><Input.TextArea rows={4} /></Form.Item>
                </section>
              ),
            },
            {
              key: 'environments',
              label: <TabLabel icon={<DatabaseOutlined />} text="环境与资源" count={environments.length} />,
              children: (
                <Form.List name="environments">
                  {(fields, { add, remove }) => (
                    <section className="project-tab-panel">
                      <div className="project-panel-head">
                        <span>环境</span>
                        <Button icon={<PlusOutlined />} onClick={() => {
                          const nextIndex = fields.length;
                          add(createEnvironment());
                          setActiveEnvironment(String(nextIndex));
                        }}>添加环境</Button>
                      </div>
                      {fields.length ? (
                        <Tabs
                          className="environment-tabs"
                          activeKey={String(Math.min(Number(activeEnvironment), fields.length - 1))}
                          onChange={setActiveEnvironment}
                          items={fields.map((field, index) => ({
                            key: String(index),
                            label: environments[index]?.name || `环境 ${index + 1}`,
                            children: (
                              <EnvironmentEditor
                                fieldName={field.name}
                                environment={environments[index]}
                                onRemove={() => {
                                  remove(field.name);
                                  setActiveEnvironment(String(Math.max(0, index - 1)));
                                }}
                              />
                            ),
                          }))}
                        />
                      ) : <Empty description="还没有环境" />}
                    </section>
                  )}
                </Form.List>
              ),
            },
            {
              key: 'relations',
              label: <TabLabel icon={<ApartmentOutlined />} text="项目关系" count={relations.length} />,
              children: (
                <section className="project-tab-panel">
                  <Form.List name="relations">
                    {(fields, { add, remove }) => (
                      <>
                        <div className="project-panel-head">
                          <span>项目关系</span>
                          <Button icon={<PlusOutlined />} onClick={() => add({ direction: 'DOWNSTREAM', relationType: 'DEPENDENCY' })}>添加关系</Button>
                        </div>
                        {fields.map((field) => (
                          <div className="form-list-row relation-row" key={field.key}>
                            <Form.Item {...field} name={[field.name, 'relatedProjectId']} rules={[{ required: true, message: '选择项目' }]}>
                              <Select placeholder="关联项目" options={relationOptions} />
                            </Form.Item>
                            <Form.Item {...field} name={[field.name, 'direction']} rules={[{ required: true, message: '选择方向' }]}>
                              <Select options={[{ label: '上游', value: 'UPSTREAM' }, { label: '下游', value: 'DOWNSTREAM' }, { label: '双向', value: 'BIDIRECTIONAL' }]} />
                            </Form.Item>
                            <Form.Item {...field} name={[field.name, 'relationType']} rules={[{ required: true, message: '选择类型' }]}>
                              <Select options={[{ label: '服务依赖', value: 'DEPENDENCY' }, { label: '前后端', value: 'FRONTEND_BACKEND' }, { label: '共享资源', value: 'SHARED_RESOURCE' }, { label: '其他', value: 'OTHER' }]} />
                            </Form.Item>
                            <Form.Item {...field} name={[field.name, 'description']}><Input placeholder="关系说明" /></Form.Item>
                            <Tooltip title="删除关系"><Button type="text" danger icon={<DeleteOutlined />} onClick={() => remove(field.name)} /></Tooltip>
                          </div>
                        ))}
                        {!fields.length && <Empty description="还没有项目关系" />}
                      </>
                    )}
                  </Form.List>
                </section>
              ),
            },
          ]}
        />
      </Form>
    </div>
  );
}

function EnvironmentEditor({ fieldName, environment, onRemove }: {
  fieldName: number;
  environment?: Environment;
  onRemove: () => void;
}) {
  return (
    <div className="environment-editor">
      <div className="environment-editor-head">
        <strong>{environment?.name || '未命名环境'}</strong>
        <Popconfirm title="删除这个环境及其资源？" onConfirm={onRemove}>
          <Button danger icon={<DeleteOutlined />}>删除环境</Button>
        </Popconfirm>
      </div>
      <div className="environment-basic-grid">
        <Form.Item name={[fieldName, 'name']} label="环境名称" rules={[{ required: true, message: '填写环境名称' }]}><Input /></Form.Item>
        <Form.Item name={[fieldName, 'code']} label="环境编码" rules={[{ required: true, message: '填写环境编码' }]}><Input /></Form.Item>
        <Form.Item name={[fieldName, 'deploymentBranch']} label="部署分支"><Input placeholder="例如：main" /></Form.Item>
      </div>
      <Tabs
        className="resource-tabs"
        items={[
          {
            key: 'repositories',
            label: <TabLabel icon={<BranchesOutlined />} text="代码仓库" count={environment?.repositories?.length || 0} />,
            children: <RepositoryEditor environmentField={fieldName} />,
          },
          {
            key: 'databases',
            label: <TabLabel icon={<DatabaseOutlined />} text="数据库" count={environment?.databases?.length || 0} />,
            children: <DatabaseEditor environmentField={fieldName} />,
          },
        ]}
      />
    </div>
  );
}

function RepositoryEditor({ environmentField }: { environmentField: number }) {
  return (
    <Form.List name={[environmentField, 'repositories']}>
      {(fields, { add, remove }) => (
        <div className="resource-editor">
          <div className="resource-editor-actions">
            <Button icon={<PlusOutlined />} onClick={() => add({ code: '', name: '', description: '', repositoryUrl: '', baseBranch: 'main', primaryRepo: false })}>添加仓库</Button>
          </div>
          {fields.map((field) => (
            <div className="repository-editor-row" key={field.key}>
              <div className="repository-editor-grid">
                <Form.Item {...field} name={[field.name, 'code']} label="仓库编码" rules={[{ required: true, message: '填写编码' }]}><Input /></Form.Item>
                <Form.Item {...field} name={[field.name, 'name']} label="仓库名称" rules={[{ required: true, message: '填写名称' }]}><Input /></Form.Item>
                <Form.Item {...field} name={[field.name, 'repositoryUrl']} label="Git 地址" rules={[{ required: true, message: '填写仓库地址' }]}><Input /></Form.Item>
                <Form.Item {...field} name={[field.name, 'baseBranch']} label="基准分支"><Input /></Form.Item>
                <Form.Item {...field} name={[field.name, 'primaryRepo']} label="主仓库" valuePropName="checked"><Checkbox>是</Checkbox></Form.Item>
                <Tooltip title="删除仓库"><Button className="resource-delete-button" type="text" danger icon={<DeleteOutlined />} onClick={() => remove(field.name)} /></Tooltip>
              </div>
              <Form.Item {...field} name={[field.name, 'description']} label="仓库说明"><Input /></Form.Item>
            </div>
          ))}
          {!fields.length && <Empty description="还没有代码仓库" />}
        </div>
      )}
    </Form.List>
  );
}

function DatabaseEditor({ environmentField }: { environmentField: number }) {
  return (
    <Form.List name={[environmentField, 'databases']}>
      {(fields, { add, remove }) => (
        <div className="resource-editor">
          <div className="resource-editor-actions">
            <Button icon={<PlusOutlined />} onClick={() => add({ code: '', name: '', url: '', username: '', password: '', schemaHint: '' })}>添加数据库</Button>
          </div>
          {fields.map((field) => (
            <div className="database-editor-row" key={field.key}>
              <div className="database-editor-grid">
                <Form.Item {...field} name={[field.name, 'code']} label="数据库编码" rules={[{ required: true, message: '填写编码' }]}><Input /></Form.Item>
                <Form.Item {...field} name={[field.name, 'name']} label="数据库名称" rules={[{ required: true, message: '填写名称' }]}><Input /></Form.Item>
                <Form.Item {...field} name={[field.name, 'url']} label="JDBC URL" rules={[{ required: true, message: '填写 JDBC URL' }]}><Input /></Form.Item>
                <Form.Item {...field} name={[field.name, 'username']} label="用户名"><Input /></Form.Item>
                <Form.Item {...field} name={[field.name, 'password']} label="密码"><Input.Password /></Form.Item>
                <Form.Item {...field} name={[field.name, 'schemaHint']} label="库表范围"><Input /></Form.Item>
              </div>
              <Tooltip title="删除数据库"><Button className="resource-delete-button" type="text" danger icon={<DeleteOutlined />} onClick={() => remove(field.name)} /></Tooltip>
            </div>
          ))}
          {!fields.length && <Empty description="还没有数据库" />}
        </div>
      )}
    </Form.List>
  );
}

function TabLabel({ icon, text, count }: { icon: React.ReactNode; text: string; count?: number }) {
  return <span className="detail-tab-label">{icon}<span>{text}</span>{count !== undefined && <small>{count}</small>}</span>;
}
