import { Tabs } from 'antd';
import { useSearchParams } from 'react-router-dom';
import '../styles/settings.css';
import { DefinitionPage } from './DefinitionPage';
import { McpManagementPanel } from './McpManagementPanel';

export function CapabilityManagementPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = searchParams.get('tab') === 'mcp' ? 'mcp' : 'skill';

  return (
    <div className="admin-page-surface flex h-full min-h-0 flex-col p-3">
      <Tabs
        className="capability-management-tabs"
        activeKey={activeTab}
        onChange={(tab) => setSearchParams({ tab }, { replace: true })}
        items={[
          { key: 'mcp', label: 'MCP', children: <McpManagementPanel /> },
          { key: 'skill', label: 'SKILL', children: <DefinitionPage kind="capability" embedded /> },
        ]}
      />
    </div>
  );
}
