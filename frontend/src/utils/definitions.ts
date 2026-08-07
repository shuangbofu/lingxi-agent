import type { AgentScenario } from '../types/api';

export function definitionScenarioOptions(definitions: AgentScenario[]) {
  const options = new Map<string, string>();
  definitions.forEach((item) => {
    if (item.scenario) {
      options.set(item.scenario, item.name || item.scenario);
    }
  });
  return Array.from(options.entries()).map(([value, label]) => ({ value, label }));
}
