import { Button } from 'antd';
import { Sparkle } from '@phosphor-icons/react';
import { useNavigate } from 'react-router-dom';
import type { AgentScenario, TaskItem } from '../types/api';
import { displayTaskType } from '../utils/format';

interface TaskRecommendedScenariosProps {
  task: TaskItem;
  scenarios: AgentScenario[];
  admin?: boolean;
}

export function TaskRecommendedScenarios({ task, scenarios, admin = false }: TaskRecommendedScenariosProps) {
  const navigate = useNavigate();
  const targets = (task.recommendedScenarioCodes || [])
    .map((code) => scenarios.find((scenario) => scenario.code === code && scenario.enabled))
    .filter((scenario): scenario is AgentScenario => Boolean(scenario));
  if (task.status !== 'SUCCESS' || targets.length === 0) {
    return null;
  }

  function openRecommendedTask(target: AgentScenario) {
    const params = new URLSearchParams({
      scenarioCode: target.code,
      sourceTaskId: String(task.id),
      prefill: `基于上一轮「${displayTaskType(task.scenario, task.scenarioName)}」的结论继续处理：${task.userInput}`,
    });
    navigate(`/?${params.toString()}`);
  }

  return (
    <div className="task-recommended-actions">
      {targets.map((target) => (
        <Button
          key={target.code}
          size="small"
          icon={<Sparkle size={14} weight="fill" />}
          onClick={() => openRecommendedTask(target)}
        >
          {admin ? target.name : `继续${target.name}`}
        </Button>
      ))}
    </div>
  );
}
