import { Button, Form, Input } from 'antd';
import { useEffect, useState } from 'react';
import '../styles/task-workspace.css';
import type { CSSProperties } from 'react';
import { LockKey, User } from '@phosphor-icons/react';
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import type { LoginDemoDefinition, PublicScenario } from '../types/api';
import { APP_NAME } from '../constants/app';
import { AppLogo } from '../components/AppLogo';
import { AppBrandText } from '../components/AppBrandText';
import { DefinitionIcon } from '../components/DefinitionIcon';
import { LoginDemo } from '../components/LoginDemo';
import { getPublicLoginConfig } from '../api/lingxi';

const LOGIN_SCENARIO_LIMIT = 4;
const LOGIN_SCENARIO_COLORS = ['#0f766e', '#2563eb', '#7c3aed', '#b45309'];

interface LoginFormValues {
  username?: string;
  password: string;
  confirmPassword?: string;
}

export function LoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { authenticated, login, setupInitialAdmin } = useAuth();
  const [form] = Form.useForm<LoginFormValues>();
  const [scenarios, setScenarios] = useState<PublicScenario[]>();
  const [demo, setDemo] = useState<LoginDemoDefinition>();
  const [initializationRequired, setInitializationRequired] = useState<boolean>();
  const [submitting, setSubmitting] = useState(false);
  const redirectPath = safeRedirectPath(searchParams.get('redirect'));

  useEffect(() => {
    let active = true;
    void getPublicLoginConfig()
      .then((config) => {
        if (active) {
          setScenarios(config.scenarios);
          setDemo(config.demo);
          setInitializationRequired(config.initializationRequired);
        }
      })
      .catch(() => {
        if (active) {
          setScenarios([]);
          setDemo(undefined);
          setInitializationRequired(false);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  if (authenticated) {
    return <Navigate to={redirectPath} replace />;
  }

  async function submit(values: LoginFormValues) {
    setSubmitting(true);
    try {
      if (initializationRequired) {
        await setupInitialAdmin({ password: values.password });
      } else {
        await login({ username: values.username || '', password: values.password });
      }
      navigate(redirectPath, { replace: true });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="login-shell min-h-screen bg-app">
      <section className="login-stage">
        <div className="login-product">
          <div className="login-product-top">
            <div className="login-product-mark">
              <AppLogo />
            </div>
            <AppBrandText className="login-product-brand" />
          </div>
          <div className="login-product-main">
            <div className="login-kicker">{initializationRequired ? '首次初始化' : '灵析已就绪'}</div>
            <h1>{scenarios?.length ? `${scenarios.length} 个智能场景，等你来发问` : '智能分析，随时响应'}</h1>
          </div>
          {demo && scenarios
            ? <LoginDemo definition={demo} scenarios={scenarios} />
            : <div className="login-agent-demo" aria-hidden="true" />}
          <div className="login-scenario-grid" aria-label="现有分析场景">
            {scenarios === undefined && Array.from({ length: LOGIN_SCENARIO_LIMIT }, (_, index) => (
              <div className="login-scenario login-scenario-loading" key={index} aria-hidden="true">
                <span className="login-scenario-icon" />
                <span className="login-scenario-copy"><i /><i /></span>
              </div>
            ))}
            {scenarios?.slice(0, LOGIN_SCENARIO_LIMIT).map((scenario, index) => (
              <div
                className="login-scenario"
                key={scenario.code}
                style={{ '--login-scenario-accent': loginScenarioColor(scenario.color, index) } as CSSProperties}
              >
                <span className="login-scenario-icon">
                  <DefinitionIcon src={scenario.iconUrl} label={scenario.name} size={38} />
                </span>
                <span className="login-scenario-copy">
                  <strong>{scenario.name}</strong>
                  <span>{scenario.slogan || scenario.description || '智能分析场景'}</span>
                </span>
              </div>
            ))}
            {scenarios?.length === 0 && (
              <div className="login-scenario-empty">登录后查看可用分析场景</div>
            )}
          </div>
        </div>
        <section className="login-card">
          <div className="mb-4">
            <div className="text-lg font-semibold text-neutral-900">
              {initializationRequired ? '设置管理员密码' : '欢迎回来'}
            </div>
            <div className="mt-1 text-sm text-neutral-500">
              {initializationRequired ? '完成后将直接进入系统' : '登录，继续你的分析'}
            </div>
          </div>
          <Form form={form} layout="vertical" onFinish={submit}>
            {!initializationRequired && (
              <Form.Item label="账号" name="username" rules={[{ required: true, message: '请输入账号' }]}>
                <Input prefix={<User size={16} />} placeholder="请输入账号" />
              </Form.Item>
            )}
            <Form.Item
              label={initializationRequired ? '管理员密码' : '密码'}
              name="password"
              rules={initializationRequired
                ? [
                    { required: true, message: '请设置管理员密码' },
                    { min: 8, max: 128, message: '密码长度需要为 8 至 128 位' },
                  ]
                : [{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                prefix={<LockKey size={16} />}
                placeholder={initializationRequired ? '设置管理员密码' : '请输入密码'}
              />
            </Form.Item>
            {initializationRequired && (
              <Form.Item
                label="确认密码"
                name="confirmPassword"
                dependencies={['password']}
                rules={[
                  { required: true, message: '请再次输入管理员密码' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      return !value || getFieldValue('password') === value
                        ? Promise.resolve()
                        : Promise.reject(new Error('两次输入的密码不一致'));
                    },
                  }),
                ]}
              >
                <Input.Password prefix={<LockKey size={16} />} placeholder="再次输入管理员密码" />
              </Form.Item>
            )}
            <Button
              type="primary"
              htmlType="submit"
              block
              loading={submitting}
              disabled={initializationRequired === undefined}
            >
              {initializationRequired ? '完成初始化' : `立即进入${APP_NAME}`}
            </Button>
          </Form>
        </section>
      </section>
    </div>
  );
}

function loginScenarioColor(color: string | undefined, index: number) {
  const normalized = color?.trim();
  return normalized && /^#[0-9a-f]{6}$/i.test(normalized)
    ? normalized
    : LOGIN_SCENARIO_COLORS[index % LOGIN_SCENARIO_COLORS.length];
}

function safeRedirectPath(value: string | null) {
  if (!value || value.includes('#/login')) {
    return '/';
  }
  const hashIndex = value.indexOf('#');
  const hashPath = hashIndex >= 0 ? value.slice(hashIndex + 1) : value;
  return hashPath.startsWith('/') ? hashPath : '/';
}
