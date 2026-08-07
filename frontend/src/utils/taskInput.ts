import type { TaskInputValue, TaskInteractionAnswerValue, TaskInteractionField, TaskInteractionItem, TaskItem } from '../types/api';

interface PromptParameterDefinition {
  key?: string;
  name?: string;
}

export function taskInputLabelMap(task: TaskItem) {
  const result = new Map<string, string>();
  for (const parameter of parsePromptParameterDefinitions(task.prompt)) {
    if (parameter.key && parameter.name) {
      result.set(parameter.key, parameter.name);
    }
  }
  for (const interaction of task.interactions || []) {
    if (interaction.contextKey) {
      result.set(interaction.contextKey, interaction.question);
    }
    for (const field of interaction.fields || []) {
      const key = field.contextKey || field.key;
      if (key && field.label) {
        result.set(key, field.label);
      }
    }
  }
  return result;
}

export function taskInputLabel(item: TaskInputValue, labels: Map<string, string>) {
  return taskParameterLabel(item.key, labels);
}

export function taskDisplayParameters(task: TaskItem) {
  const labels = taskInputLabelMap(task);
  const values = new Map<string, string>();
  for (const item of task.inputValues || []) {
    putParameterValue(values, item.key, item.value);
  }
  for (const interaction of task.interactions || []) {
    if (interaction.status !== 'ANSWERED') {
      continue;
    }
    if (interaction.inputType === 'FORM') {
      putFormAnswerValues(values, interaction.fields || [], interaction.answerValues || []);
    } else {
      putParameterValue(values, interaction.contextKey, interactionAnswerValue(interaction));
    }
  }
  return Array.from(values.entries())
    .filter(([key]) => key !== 'userInput')
    .map(([key, value]) => ({
      key,
      label: taskParameterLabel(key, labels),
      value,
    }));
}

function taskParameterLabel(key: string, labels: Map<string, string>) {
  return labels.get(key) || key;
}

function putFormAnswerValues(values: Map<string, string>, fields: TaskInteractionField[], answers: TaskInteractionAnswerValue[]) {
  const fieldMap = new Map(fields.map((field) => [field.key, field]));
  for (const answer of answers) {
    const field = fieldMap.get(answer.key);
    putParameterValue(values, field?.contextKey || field?.key || answer.key, answerValue(answer));
  }
}

function interactionAnswerValue(interaction: TaskInteractionItem) {
  if (interaction.selectedValues?.length) {
    return interaction.selectedValues.join(',');
  }
  return interaction.answerText;
}

function answerValue(answer: TaskInteractionAnswerValue) {
  if (answer.selectedValues?.length) {
    return answer.selectedValues.join(',');
  }
  return answer.value;
}

function putParameterValue(values: Map<string, string>, key: string | undefined, value: unknown) {
  const normalizedKey = key?.trim();
  const normalizedValue = value === undefined || value === null ? '' : String(value).trim();
  if (!normalizedKey || !normalizedValue) {
    return;
  }
  values.set(normalizedKey, normalizedValue);
}

function parsePromptParameterDefinitions(prompt?: string): PromptParameterDefinition[] {
  if (!prompt) {
    return [];
  }
  const match = prompt.match(/parameterDefinitions:\s*\n(\[[\s\S]*?\])\s*\n\n# Source Task Context/);
  if (!match) {
    return [];
  }
  try {
    const parsed = JSON.parse(match[1]);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}
