import { useEffect, useState } from 'react';
import { Button, DatePicker, Form, Input, Segmented, Skeleton, message } from 'antd';
import '../styles/profile.css';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { CaretLeft, ChartBar, Cpu, LockKey, UserCircle } from '@phosphor-icons/react';
import { useLocation, useNavigate } from 'react-router-dom';
import { changePassword, getProfile, getProfileUsage } from '../api/lingxi';
import type { DashboardTokenDimension, DashboardTokenUsage, Profile, UserUsageQuotaPeriod } from '../types/api';
import { AppTag } from '../components/AppTag';
import { UserAvatar } from '../components/UserAvatar';
import { formatTokenCount } from '../utils/format';
import { useAuth } from '../context/AuthContext';

type PasswordFormValues = {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
};

type UsagePeriod = 'day' | 'week' | 'month' | 'custom';

interface ProfilePageProps {
  /** 嵌入模式（对话页 modal 内）：不渲染页面级导航按钮 */
  embedded?: boolean;
}

export function ProfilePage({ embedded = false }: ProfilePageProps = {}) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useAuth();
  const [profile, setProfile] = useState<Profile>();
  const [usage, setUsage] = useState<DashboardTokenUsage>();
  const [loading, setLoading] = useState(true);
  const [usageLoading, setUsageLoading] = useState(true);
  const [usagePeriod, setUsagePeriod] = useState<UsagePeriod>('week');
  const [usageRange, setUsageRange] = useState<[Dayjs, Dayjs]>(() => usagePeriodRange('week'));
  const [passwordSubmitting, setPasswordSubmitting] = useState(false);
  const [passwordForm] = Form.useForm<PasswordFormValues>();

  useEffect(() => {
    getProfile()
      .then(setProfile)
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    setUsageLoading(true);
    getProfileUsage({
      createdStart: usageRange[0].format('YYYY-MM-DDTHH:mm:ss'),
      createdEnd: usageRange[1].format('YYYY-MM-DDTHH:mm:ss'),
    })
      .then(setUsage)
      .finally(() => setUsageLoading(false));
  }, [usageRange]);

  async function handleChangePassword() {
    const values = await passwordForm.validateFields();
    setPasswordSubmitting(true);
    try {
      await changePassword({ oldPassword: values.oldPassword, newPassword: values.newPassword });
      passwordForm.resetFields();
      message.success('密码已修改');
    } finally {
      setPasswordSubmitting(false);
    }
  }

  const summary = usage?.summary;
  const returnPath = profileReturnPath(location.state) || (user?.role === 'ADMIN' ? '/admin/dashboard' : '/');

  function selectUsagePeriod(value: UsagePeriod) {
    setUsagePeriod(value);
    if (value !== 'custom') {
      setUsageRange(usagePeriodRange(value));
    }
  }

  return (
    <div className="profile-page">
      <div className="profile-content">
        <div className="profile-headline">
          <UserCircle size={20} weight="fill" />
          <div className="min-w-0"><h1>个人中心</h1></div>
          <div className="profile-actions">
            {!embedded && (
              <>
                <Button
                  icon={<Cpu size={16} />}
                  onClick={() => navigate('/profile/models', { state: { from: '/profile', profileFrom: returnPath } })}
                >模型配置</Button>
                <Button icon={<CaretLeft size={16} />} onClick={() => navigate(returnPath, { replace: true })}>返回</Button>
              </>
            )}
          </div>
        </div>
        <section className="profile-header-card">
          {loading ? (
            <Skeleton active avatar paragraph={{ rows: 1 }} />
          ) : (
            <>
              <UserAvatar avatarUrl={profile?.avatarUrl} name={profile?.displayName || profile?.username} size={52} />
              <div className="profile-identity">
                <div className="profile-name">{profile?.displayName || profile?.username}</div>
                <div className="profile-account">{profile?.username}</div>
              </div>
              <div className="profile-tags">
                <AppTag tone="slate">{profile?.roleName || '用户'}</AppTag>
              </div>
            </>
          )}
        </section>

        <section className="profile-section">
          <div className="profile-section-title">
            <ChartBar size={18} weight="fill" />
            <span>我的用量</span>
            <em>{usageRangeLabel(usageRange)}</em>
            <div className="profile-usage-toolbar">
              <Segmented
                className="app-emphasis-segmented profile-usage-period"
                size="small"
                value={usagePeriod}
                options={[
                  { label: '今天', value: 'day' },
                  { label: '本周', value: 'week' },
                  { label: '本月', value: 'month' },
                  { label: '自定义', value: 'custom' },
                ]}
                onChange={(value) => selectUsagePeriod(value as UsagePeriod)}
              />
              {usagePeriod === 'custom' && (
                <DatePicker.RangePicker
                  allowClear={false}
                  value={usageRange}
                  onChange={(value) => {
                    if (value?.[0] && value?.[1]) {
                      setUsageRange([value[0].startOf('day'), value[1].endOf('day')]);
                    }
                  }}
                />
              )}
            </div>
          </div>
          <div className="profile-quota-list">
            <ProfileQuota label="今日配额" quota={profile?.usageQuota?.daily} />
            <ProfileQuota label="本周配额" quota={profile?.usageQuota?.weekly} />
            <ProfileQuota label="本月配额" quota={profile?.usageQuota?.monthly} />
          </div>
          <div className="profile-usage-grid">
            <UsageCard loading={usageLoading} label="请求" value={formatTokenCount(summary?.requestCount)} detail={`${formatTokenCount(summary?.taskCount)} 个任务`} />
            <UsageCard loading={usageLoading} label="总 Token" value={formatTokenCount(summary?.totalTokens)} detail={`输入 ${formatTokenCount(summary?.inputTokens)} · 输出 ${formatTokenCount(summary?.outputTokens)}`} />
            <UsageCard loading={usageLoading} label="平均用量" value={formatTokenCount(summary?.averageTokens)} detail={`每请求 ${formatTokenCount(averageByRequest(summary))}`} />
            <UsageCard loading={usageLoading} label="成功率" value={successRate(summary)} detail={`成功 ${formatTokenCount(summary?.successCount)} · 失败 ${formatTokenCount(summary?.failedCount)}`} />
          </div>
        </section>

        <section className="profile-settings-grid profile-settings-grid-single">
          <article className="profile-setting-card">
            <div className="profile-setting-heading">
              <span className="profile-setting-icon profile-setting-icon-lock"><LockKey size={19} weight="fill" /></span>
              <div>
                <h2>修改密码</h2>
                <p>账号：{profile?.username || '-'}</p>
              </div>
            </div>
            <Form form={passwordForm} layout="vertical" onFinish={handleChangePassword}>
              <Form.Item label="原密码" name="oldPassword" rules={[{ required: true, message: '请输入原密码' }]}>
                <Input.Password autoComplete="current-password" placeholder="请输入原密码" />
              </Form.Item>
              <div className="profile-password-row">
                <Form.Item label="新密码" name="newPassword" rules={[{ required: true, message: '请输入新密码' }]}>
                  <Input.Password autoComplete="new-password" placeholder="请输入新密码" />
                </Form.Item>
                <Form.Item
                  label="确认新密码"
                  name="confirmPassword"
                  dependencies={['newPassword']}
                  rules={[
                    { required: true, message: '请再次输入新密码' },
                    ({ getFieldValue }) => ({
                      validator(_, value) {
                        return !value || getFieldValue('newPassword') === value
                          ? Promise.resolve()
                          : Promise.reject(new Error('两次输入的新密码不一致'));
                      },
                    }),
                  ]}
                >
                  <Input.Password autoComplete="new-password" placeholder="请再次输入新密码" />
                </Form.Item>
              </div>
              <div className="profile-form-actions">
                <Button type="primary" htmlType="submit" loading={passwordSubmitting}>修改密码</Button>
              </div>
            </Form>
          </article>
        </section>
      </div>
    </div>
  );
}

function UsageCard({ label, value, detail, loading }: { label: string; value: string; detail: string; loading: boolean }) {
  return (
    <div className="profile-usage-card">
      {loading ? <Skeleton active title={false} paragraph={{ rows: 2 }} /> : <><span>{label}</span><strong>{value}</strong><small>{detail}</small></>}
    </div>
  );
}

function ProfileQuota({ label, quota }: { label: string; quota?: UserUsageQuotaPeriod }) {
  return (
    <div className="profile-quota">
      <div className="profile-quota-summary">
        <span>{label}</span>
        <strong>{formatTokenCount(quota?.tokenUsed || 0)}</strong>
        <small>{quota?.limited ? ` / ${formatTokenCount(quota.tokenLimit)}` : '不限额'}</small>
        <em>{quota?.limited ? `${quota.usagePercent || 0}%` : '未限制'}</em>
      </div>
      <div className="profile-quota-progress">
        <span style={{ width: `${profileQuotaWidth(quota?.usagePercent)}%` }} />
      </div>
      {quota?.inherited && <div className="profile-quota-source">继承全局配额</div>}
    </div>
  );
}

function averageByRequest(summary?: DashboardTokenDimension) {
  const requestCount = summary?.requestCount || 0;
  return requestCount === 0 ? 0 : Math.round((summary?.totalTokens || 0) / requestCount);
}

function successRate(summary?: DashboardTokenDimension) {
  const successCount = summary?.successCount || 0;
  const finishedCount = successCount + (summary?.failedCount || 0) + (summary?.canceledCount || 0);
  return `${finishedCount === 0 ? '0' : ((successCount / finishedCount) * 100).toFixed(1).replace(/\.0$/, '')}%`;
}

function profileQuotaWidth(percent?: number) {
  return Math.min(Math.max(percent || 0, 0), 100);
}

function usagePeriodRange(period: Exclude<UsagePeriod, 'custom'>): [Dayjs, Dayjs] {
  const now = dayjs();
  if (period === 'day') {
    return [now.startOf('day'), now.endOf('day')];
  }
  if (period === 'month') {
    return [now.startOf('month'), now.endOf('month')];
  }
  const weekday = now.day() || 7;
  const start = now.subtract(weekday - 1, 'day').startOf('day');
  return [start, start.add(6, 'day').endOf('day')];
}

function usageRangeLabel(range: [Dayjs, Dayjs]) {
  return `${range[0].format('MM-DD')} 至 ${range[1].format('MM-DD')}`;
}

function profileReturnPath(state: unknown) {
  if (!state || typeof state !== 'object' || !('from' in state)) {
    return undefined;
  }
  const from = (state as { from?: unknown }).from;
  return typeof from === 'string' && from.startsWith('/') && from !== '/profile' ? from : undefined;
}
