import { ApiOutlined, CloudUploadOutlined, ExperimentOutlined, LinkOutlined } from '@ant-design/icons';
import { Alert, Button, Checkbox, Input, Spin, Tabs, message } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { demoApi } from '../api/demo';
import { PageHeading } from '../components/PageHeading';
import type { BundleCapability, BundleCatalog, BundleScenario, DemoInfo } from '../types';

const LINGXI_URL_KEY = 'lingxi-resource-demo:platform-url';
const DEFAULT_LINGXI_URL = 'http://localhost:8080';
const LEGACY_DEVELOPMENT_URL = 'http://localhost:5173';
const DEFAULT_SCENARIO_CODES = new Set(['business-question', 'document-understanding', 'log-analysis']);
const IMPORT_PROBE = 'LINGXI_DEFINITION_IMPORT_PROBE';
const IMPORT_READY = 'LINGXI_DEFINITION_IMPORT_READY';
const IMPORT_OFFER = 'LINGXI_DEFINITION_IMPORT_OFFER';

export function SetupPage() {
  const [info, setInfo] = useState<DemoInfo>();
  const [catalog, setCatalog] = useState<BundleCatalog>();
  const [catalogError, setCatalogError] = useState(false);
  const [lingxiUrl, setLingxiUrl] = useState(() => {
    const savedUrl = localStorage.getItem(LINGXI_URL_KEY);
    if (!savedUrl || savedUrl.replace(/\/+$/, '') === LEGACY_DEVELOPMENT_URL) {
      localStorage.setItem(LINGXI_URL_KEY, DEFAULT_LINGXI_URL);
      return DEFAULT_LINGXI_URL;
    }
    return savedUrl;
  });
  const [selectedCapabilities, setSelectedCapabilities] = useState<string[]>([]);
  const [selectedScenarios, setSelectedScenarios] = useState<string[]>([]);
  const [waiting, setWaiting] = useState(false);

  useEffect(() => {
    void demoApi.info()
      .then((value) => {
        setInfo(value);
        return demoApi.catalog(value.baseUrl);
      })
      .then((value) => {
        setCatalog(value);
        setSelectedCapabilities(value.capabilities.map((item) => item.code));
        setSelectedScenarios(value.scenarios.filter((item) => DEFAULT_SCENARIO_CODES.has(item.code)).map((item) => item.code));
      })
      .catch(() => setCatalogError(true));
  }, []);

  const selectedCount = selectedCapabilities.length + selectedScenarios.length;
  const selectedCapabilitySet = useMemo(() => new Set(selectedCapabilities), [selectedCapabilities]);

  function toggleCapability(code: string, checked: boolean) {
    setSelectedCapabilities((current) => checked ? [...new Set([...current, code])] : current.filter((item) => item !== code));
  }

  function toggleScenario(scenario: BundleScenario, checked: boolean) {
    setSelectedScenarios((current) => checked ? [...new Set([...current, scenario.code])] : current.filter((item) => item !== scenario.code));
    if (checked && catalog) {
      const bundledCapabilityCodes = new Set(catalog.capabilities.map((item) => item.code));
      setSelectedCapabilities((current) => [
        ...new Set([...current, ...scenario.capabilities.filter((code) => bundledCapabilityCodes.has(code))]),
      ]);
    }
  }

  function openInstaller() {
    if (!info || !catalog || !lingxiUrl.trim() || selectedCount === 0) {
      return;
    }
    const target = lingxiUrl.trim();
    let targetBase: string;
    let targetOrigin: string;
    try {
      const targetUrl = new URL(target);
      targetUrl.hash = '';
      targetUrl.search = '';
      targetBase = targetUrl.toString().replace(/\/+$/, '');
      targetOrigin = targetUrl.origin;
    } catch {
      message.warning('请输入正确的 Lingxi 页面地址');
      return;
    }
    localStorage.setItem(LINGXI_URL_KEY, targetBase);
    const lingxiWindow = window.open(`${targetBase}/#/admin/scenarios`, 'lingxi-definition-import');
    if (!lingxiWindow) {
      message.warning('浏览器阻止了 Lingxi 页面，请允许本站打开新窗口');
      return;
    }
    const targetWindow = lingxiWindow;

    const offer = {
      type: IMPORT_OFFER,
      sourceName: catalog.name,
      capabilities: catalog.capabilities.filter((item) => selectedCapabilities.includes(item.code)),
      configurations: catalog.configurations.filter((item) => selectedCapabilities.includes(item.capabilityCode)),
      scenarios: catalog.scenarios.filter((item) => selectedScenarios.includes(item.code)),
    };
    setWaiting(true);
    const probe = window.setInterval(() => targetWindow.postMessage({ type: IMPORT_PROBE }, targetOrigin), 800);
    targetWindow.postMessage({ type: IMPORT_PROBE }, targetOrigin);
    const timeout = window.setTimeout(() => {
      window.clearInterval(probe);
      window.removeEventListener('message', handleReady);
      setWaiting(false);
      message.warning('Lingxi 尚未准备好，请确认已登录后重新点击导入');
    }, 120000);
    function handleReady(event: MessageEvent) {
      if (event.source !== targetWindow || event.origin !== targetOrigin || event.data?.type !== IMPORT_READY) {
        return;
      }
      window.clearTimeout(timeout);
      window.clearInterval(probe);
      window.removeEventListener('message', handleReady);
      targetWindow.postMessage(offer, targetOrigin);
      targetWindow.focus();
      setWaiting(false);
    }
    window.addEventListener('message', handleReady);
  }

  return (
    <div>
      <PageHeading
        title="接入 Lingxi"
        description="选择需要演示的能力和场景，在 Lingxi 管理端确认安装"
      />

      <section className="setup-section">
        <div className="setup-section-title">{catalog?.name || '示例套件'}</div>
        {catalog ? (
          <Tabs
            className="setup-tabs"
            items={[
              {
                key: 'scenarios',
                label: <span><ExperimentOutlined />示例场景（{catalog.scenarios.length}）</span>,
                children: (
                  <div className="setup-scenario-list">
                    <SelectionToolbar
                      checked={selectedScenarios.length === catalog.scenarios.length}
                      indeterminate={selectedScenarios.length > 0 && selectedScenarios.length < catalog.scenarios.length}
                      label={`已选择 ${selectedScenarios.length} 个场景`}
                      onChange={(checked) => setSelectedScenarios(checked ? catalog.scenarios.map((item) => item.code) : [])}
                    />
                    {catalog.scenarios.map((scenario) => (
                      <label className="setup-scenario-row" key={scenario.code}>
                        <Checkbox
                          checked={selectedScenarios.includes(scenario.code)}
                          onChange={(event) => toggleScenario(scenario, event.target.checked)}
                        />
                        <DefinitionMark item={scenario} />
                        <div className="setup-definition-name">
                          <strong>{scenario.name}</strong>
                          <span>{scenario.slogan || scenario.code}</span>
                        </div>
                        <p>{scenario.description}</p>
                        <div className="setup-capability-pills">
                          {scenario.capabilities.map((code) => (
                            <span className={selectedCapabilitySet.has(code) ? 'selected' : ''} key={code}>
                              {catalog.capabilities.find((item) => item.code === code)?.name || code}
                            </span>
                          ))}
                        </div>
                      </label>
                    ))}
                  </div>
                ),
              },
              {
                key: 'capabilities',
                label: <span><ApiOutlined />包含能力（{catalog.capabilities.length}）</span>,
                children: (
                  <div className="setup-list">
                    <SelectionToolbar
                      checked={selectedCapabilities.length === catalog.capabilities.length}
                      indeterminate={selectedCapabilities.length > 0 && selectedCapabilities.length < catalog.capabilities.length}
                      label={`已选择 ${selectedCapabilities.length} 个能力`}
                      onChange={(checked) => setSelectedCapabilities(checked ? catalog.capabilities.map((item) => item.code) : [])}
                    />
                    {catalog.capabilities.map((capability) => (
                      <label className="setup-capability-row" key={capability.code}>
                        <Checkbox
                          checked={selectedCapabilities.includes(capability.code)}
                          onChange={(event) => toggleCapability(capability.code, event.target.checked)}
                        />
                        <DefinitionMark item={capability} />
                        <span className="setup-definition-name"><strong>{capability.name}</strong><small>{capability.code}</small></span>
                        <span>{capability.description}</span>
                      </label>
                    ))}
                  </div>
                ),
              },
            ]}
          />
        ) : catalogError ? (
          <Alert type="error" showIcon message="示例套件清单读取失败" />
        ) : (
          <div className="setup-loading"><Spin size="small" /><span>正在读取示例内容</span></div>
        )}
      </section>

      <section className="setup-section">
        <div className="setup-section-title">目标平台</div>
        <div className="setup-target-row">
          <Input
            prefix={<LinkOutlined />}
            value={lingxiUrl}
            onChange={(event) => setLingxiUrl(event.target.value)}
            placeholder="Lingxi 页面地址"
          />
          <Button
            type="primary"
            icon={<CloudUploadOutlined />}
            loading={waiting}
            disabled={!info || !lingxiUrl.trim() || selectedCount === 0}
            onClick={openInstaller}
          >
            导入已选（{selectedCount}）
          </Button>
        </div>
      </section>

      <Alert
        type="info"
        showIcon
        message="Lingxi 会在场景管理页展示来源和安装内容，确认后才会安装或更新。"
      />
    </div>
  );
}

function SelectionToolbar({ checked, indeterminate, label, onChange }: {
  checked: boolean;
  indeterminate: boolean;
  label: string;
  onChange: (checked: boolean) => void;
}) {
  return (
    <div className="setup-selection-toolbar">
      <Checkbox checked={checked} indeterminate={indeterminate} onChange={(event) => onChange(event.target.checked)}>全选</Checkbox>
      <span>{label}</span>
    </div>
  );
}

function DefinitionMark({ item }: { item: BundleCapability | BundleScenario }) {
  const color = 'color' in item && item.color ? item.color : '#0f766e';
  return item.iconUrl
    ? <img className="setup-definition-icon" src={item.iconUrl} alt="" />
    : <span className="setup-definition-icon setup-definition-icon-fallback" style={{ backgroundColor: color }}>{item.name.slice(0, 1)}</span>;
}
