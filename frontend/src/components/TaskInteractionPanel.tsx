import { useMemo, useState } from 'react';
import { Button, Checkbox, DatePicker, Input, Radio, Segmented, Select, Space, message } from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { answerTaskInteraction } from '../api/lingxi';
import { MarkdownText } from './MarkdownText';
import type { TaskInteractionAction, TaskInteractionAnswerAction, TaskInteractionAnswerValue, TaskInteractionField, TaskInteractionItem, TaskInteractionOption } from '../types/api';

interface TaskInteractionPanelProps {
  taskId: number;
  interactions?: TaskInteractionItem[];
  compact?: boolean;
  onAnswered?: () => void;
}

export function TaskInteractionPanel({ taskId, interactions, compact, onAnswered }: TaskInteractionPanelProps) {
  const visibleInteractions = useMemo(() => (interactions || []).filter((item) => item.status === 'PENDING'), [interactions]);
  if (visibleInteractions.length === 0) {
    return null;
  }
  return (
    <div className={compact ? 'interaction-stack interaction-stack-compact' : 'interaction-stack'}>
      {visibleInteractions.map((interaction) => (
        <InteractionCard key={interaction.id} taskId={taskId} interaction={interaction} onAnswered={onAnswered} />
      ))}
    </div>
  );
}

function InteractionCard({ taskId, interaction, onAnswered }: { taskId: number; interaction: TaskInteractionItem; onAnswered?: () => void }) {
  const [text, setText] = useState(defaultTextValue(interaction));
  const [single, setSingle] = useState<string | undefined>(defaultSingleValue(interaction));
  const [multiple, setMultiple] = useState<string[]>(defaultMultipleValue(interaction));
  const [dateValue, setDateValue] = useState<Dayjs | null>(defaultDateValue(interaction));
  const [formValues, setFormValues] = useState<Record<string, unknown>>(defaultFormValues(interaction));
  const [submitting, setSubmitting] = useState(false);
  const options = interaction.options || [];
  const actions = interaction.actions || [];

  async function submit(action: TaskInteractionAnswerAction = 'SUBMIT') {
    const selectedValues = interaction.inputType === 'SINGLE_CHOICE' || interaction.inputType === 'SELECT' || interaction.inputType === 'YES_NO'
      ? single ? [single] : []
      : interaction.inputType === 'MULTI_CHOICE'
        ? multiple
        : interaction.inputType === 'CONFIRM'
          ? single ? [single] : []
          : [];
    const answerText = inputAnswerText(interaction, text, dateValue);
    const answerValues = interaction.inputType === 'FORM' ? formAnswerValues(interaction.fields || [], formValues) : [];
    const validateInput = action === 'SUBMIT' || Boolean(actions.find((item) => item.key === action)?.validateInput);
    if (validateInput && interaction.required && !answerText && selectedValues.length === 0 && answerValues.length === 0) {
      message.warning('请先填写或选择');
      return;
    }
    if (validateInput && interaction.inputType === 'FORM' && hasMissingRequiredFormField(interaction.fields || [], formValues)) {
      message.warning('请先填写或选择');
      return;
    }
    setSubmitting(true);
    try {
      await answerTaskInteraction(taskId, interaction.id, { action, answerText, selectedValues, answerValues });
      message.success(actionMessage(action, actions));
      onAnswered?.();
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="interaction-card">
      <div className="interaction-card-head">
        <span className="interaction-card-kicker">需要你确认</span>
        <span className="interaction-card-required">{interaction.required ? '必填' : '可跳过'}</span>
      </div>
      <div className="interaction-question">{interaction.question}</div>
      {interaction.content && <div className="interaction-preview"><MarkdownText content={interaction.content} /></div>}
      {interaction.answerHint && <div className="interaction-hint">{interaction.answerHint}</div>}
      <div className="interaction-control">
        {(interaction.inputType === 'TEXT' || interaction.inputType === 'TEXTAREA') && (
          <Input.TextArea
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder={interaction.placeholder || '请输入补充信息'}
            autoSize={{ minRows: 3, maxRows: 8 }}
          />
        )}
        {interaction.inputType === 'YES_NO' && (
          <Segmented
            block
            value={single}
            onChange={(value) => setSingle(String(value))}
            options={[
              { label: '是', value: 'yes' },
              { label: '否', value: 'no' },
            ]}
          />
        )}
        {(interaction.inputType === 'DATE' || interaction.inputType === 'DATETIME') && (
          <DatePicker
            className="interaction-date-picker"
            value={dateValue}
            onChange={setDateValue}
            showTime={interaction.inputType === 'DATETIME' ? { format: 'HH:mm:ss' } : false}
            format={interaction.inputType === 'DATETIME' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'}
            placeholder={interaction.placeholder || (interaction.inputType === 'DATETIME' ? '请选择日期时间' : '请选择日期')}
          />
        )}
        {interaction.inputType === 'SELECT' && (
          <Select
            className="interaction-select"
            showSearch
            optionFilterProp="searchText"
            optionLabelProp="labelText"
            listHeight={320}
            value={single}
            onChange={setSingle}
            placeholder={interaction.placeholder || '请选择'}
            filterOption={(input, option) => String(option?.searchText || '').toLowerCase().includes(input.trim().toLowerCase())}
            notFoundContent="没有匹配项"
            options={selectOptions(options)}
          />
        )}
        {interaction.inputType === 'SINGLE_CHOICE' && (
          <Radio.Group value={single} onChange={(event) => setSingle(event.target.value)} className="interaction-option-group">
            <Space direction="vertical" size={8}>
              {options.map((option) => (
                <Radio key={option.value} value={option.value}>
                  <span className="interaction-option-label">{option.label}</span>
                  {option.description && <span className="interaction-option-desc">{option.description}</span>}
                </Radio>
              ))}
            </Space>
          </Radio.Group>
        )}
        {interaction.inputType === 'MULTI_CHOICE' && (
          <Checkbox.Group value={multiple} onChange={(value) => setMultiple(value.map(String))} className="interaction-option-group">
            <Space direction="vertical" size={8}>
              {options.map((option) => (
                <Checkbox key={option.value} value={option.value}>
                  <span className="interaction-option-label">{option.label}</span>
                  {option.description && <span className="interaction-option-desc">{option.description}</span>}
                </Checkbox>
              ))}
            </Space>
          </Checkbox.Group>
        )}
        {interaction.inputType === 'CONFIRM' && (
          <Radio.Group value={single} onChange={(event) => setSingle(event.target.value)} className="interaction-option-group">
            <Space>
              {(options.length ? options : [{ label: '确认', value: 'yes' }, { label: '取消', value: 'no' }]).map((option) => (
                <Radio.Button key={option.value} value={option.value}>{option.label}</Radio.Button>
              ))}
            </Space>
          </Radio.Group>
        )}
        {interaction.inputType === 'FORM' && (
          <div className="interaction-form">
            {(interaction.fields || []).map((field) => (
              <div className="interaction-form-field" key={field.key}>
                <div className="interaction-form-label">
                  <span>{field.label}</span>
                  {field.required !== false && <span>必填</span>}
                </div>
                {field.description && <div className="interaction-form-desc">{field.description}</div>}
                <FormFieldControl field={field} value={formValues[field.key]} onChange={(value) => setFormValues((current) => ({ ...current, [field.key]: value }))} />
              </div>
            ))}
          </div>
        )}
      </div>
      <div className="interaction-actions">
        {actions.length > 0 ? (
          actions.map((action) => (
            <Button
              key={action.key}
              type={action.style === 'primary' || action.style === 'danger' ? 'primary' : 'default'}
              danger={action.style === 'danger'}
              loading={submitting}
              onClick={() => submit(action.key)}
              title={action.description}
            >
              {action.label}
            </Button>
          ))
        ) : (
          <>
            {!interaction.required && <Button disabled={submitting} onClick={() => submit('SKIP')}>跳过</Button>}
            <Button disabled={submitting} onClick={() => submit('UNKNOWN')}>不知道</Button>
            <Button disabled={submitting} onClick={() => submit('CANCEL')}>取消</Button>
            <Button type="primary" loading={submitting} onClick={() => submit('SUBMIT')}>提交</Button>
          </>
        )}
      </div>
    </div>
  );
}

function FormFieldControl({ field, value, onChange }: { field: TaskInteractionField; value: unknown; onChange: (value: unknown) => void }) {
  const type = field.type || 'TEXT';
  if (type === 'SELECT') {
    return (
      <Select
        className="interaction-select"
        showSearch
        optionFilterProp="searchText"
        optionLabelProp="labelText"
        listHeight={320}
        value={typeof value === 'string' ? value : undefined}
        onChange={onChange}
        placeholder={field.placeholder || '请选择'}
        filterOption={(input, option) => String(option?.searchText || '').toLowerCase().includes(input.trim().toLowerCase())}
        notFoundContent="没有匹配项"
        options={selectOptions(field.options || [])}
      />
    );
  }
  if (type === 'YES_NO') {
    return (
      <Segmented
        block
        value={typeof value === 'string' ? value : undefined}
        onChange={(nextValue) => onChange(String(nextValue))}
        options={[{ label: '是', value: 'yes' }, { label: '否', value: 'no' }]}
      />
    );
  }
  if (type === 'DATE' || type === 'DATETIME') {
    return (
      <DatePicker
        className="interaction-date-picker"
        value={dayjs.isDayjs(value) ? value : null}
        onChange={onChange}
        showTime={type === 'DATETIME' ? { format: 'HH:mm:ss' } : false}
        format={type === 'DATETIME' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'}
        placeholder={field.placeholder || (type === 'DATETIME' ? '请选择日期时间' : '请选择日期')}
      />
    );
  }
  if (type === 'TEXTAREA') {
    return <Input.TextArea value={typeof value === 'string' ? value : ''} onChange={(event) => onChange(event.target.value)} placeholder={field.placeholder || '请输入'} autoSize={{ minRows: 3, maxRows: 8 }} />;
  }
  return <Input value={typeof value === 'string' ? value : ''} onChange={(event) => onChange(event.target.value)} placeholder={field.placeholder || '请输入'} />;
}

function inputAnswerText(interaction: TaskInteractionItem, text: string, dateValue: Dayjs | null) {
  if (interaction.inputType === 'TEXT' || interaction.inputType === 'TEXTAREA') {
    return text.trim();
  }
  if (interaction.inputType === 'DATE') {
    return dateValue ? dateValue.format('YYYY-MM-DD') : undefined;
  }
  if (interaction.inputType === 'DATETIME') {
    return dateValue ? dateValue.format('YYYY-MM-DD HH:mm:ss') : undefined;
  }
  return undefined;
}

function defaultTextValue(interaction: TaskInteractionItem) {
  return interaction.inputType === 'TEXT' || interaction.inputType === 'TEXTAREA' ? interaction.defaultValue || '' : '';
}

function defaultSingleValue(interaction: TaskInteractionItem) {
  const value = interaction.defaultValue?.trim();
  if (interaction.inputType === 'SINGLE_CHOICE' || interaction.inputType === 'SELECT' || interaction.inputType === 'YES_NO' || interaction.inputType === 'CONFIRM') {
    return value || interaction.options?.[0]?.value || (interaction.inputType === 'YES_NO' ? 'yes' : undefined);
  }
  return undefined;
}

function defaultMultipleValue(interaction: TaskInteractionItem) {
  if (interaction.inputType !== 'MULTI_CHOICE') {
    return [];
  }
  return (interaction.defaultValue || '').split(',').map((item) => item.trim()).filter(Boolean);
}

function defaultDateValue(interaction: TaskInteractionItem) {
  if (interaction.inputType !== 'DATE' && interaction.inputType !== 'DATETIME') {
    return null;
  }
  const value = interaction.defaultValue?.trim();
  if (!value) {
    return null;
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed : null;
}

function defaultFormValues(interaction: TaskInteractionItem) {
  if (interaction.inputType !== 'FORM') {
    return {};
  }
  const result: Record<string, unknown> = {};
  for (const field of interaction.fields || []) {
    if (!field.defaultValue) {
      continue;
    }
    if (field.type === 'DATE' || field.type === 'DATETIME') {
      const parsed = dayjs(field.defaultValue);
      result[field.key] = parsed.isValid() ? parsed : undefined;
    } else {
      result[field.key] = field.defaultValue;
    }
  }
  return result;
}

function formAnswerValues(fields: TaskInteractionField[], values: Record<string, unknown>): TaskInteractionAnswerValue[] {
  return fields.map((field) => {
    const value = values[field.key];
    if (field.type === 'DATE') {
      return { key: field.key, value: dayjs.isDayjs(value) ? value.format('YYYY-MM-DD') : undefined };
    }
    if (field.type === 'DATETIME') {
      return { key: field.key, value: dayjs.isDayjs(value) ? value.format('YYYY-MM-DD HH:mm:ss') : undefined };
    }
    return { key: field.key, value: typeof value === 'string' ? value.trim() : undefined };
  }).filter((item) => item.value);
}

function hasMissingRequiredFormField(fields: TaskInteractionField[], values: Record<string, unknown>) {
  return fields.some((field) => {
    if (field.required === false) {
      return false;
    }
    const value = values[field.key];
    if (dayjs.isDayjs(value)) {
      return !value.isValid();
    }
    return typeof value !== 'string' || value.trim() === '';
  });
}

function selectOptions(options: TaskInteractionOption[]) {
  return options.map((option) => ({
    labelText: option.label,
    searchText: [option.label, option.value, option.description].filter(Boolean).join(' '),
    label: option.description ? (
      <span className="interaction-select-option">
        <span>{option.label}</span>
        <span>{option.description}</span>
      </span>
    ) : option.label,
    value: option.value,
  }));
}

function actionMessage(action: TaskInteractionAnswerAction, actions: TaskInteractionAction[] = []) {
  const customAction = actions.find((item) => item.key === action);
  if (customAction) {
    return `已选择：${customAction.label}`;
  }
  if (action === 'SKIP') {
    return '已跳过';
  }
  if (action === 'UNKNOWN') {
    return '已记录为不知道';
  }
  if (action === 'CANCEL') {
    return '已取消本次确认';
  }
  return '已提交';
}
