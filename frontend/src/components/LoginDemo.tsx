import { Input } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import {
  ArrowsOutSimple,
  CaretRight,
  MagnifyingGlass,
  Paperclip,
  SlidersHorizontal,
} from '@phosphor-icons/react';
import type { LoginDemoDefinition, PublicScenario, TaskEventItem } from '../types/api';
import { AgentOutput } from './AgentOutput';
import { DefinitionIcon } from './DefinitionIcon';
import { ProviderTypeIcon } from './ProviderTypeIcon';
import { RuntimeIcon } from './RuntimeIcon';

interface LoginDemoProps {
  definition: LoginDemoDefinition;
  scenarios: PublicScenario[];
}

interface LoginDemoPlayback {
  phases: TaskEventItem[][];
  delays: number[];
}

export function LoginDemo({ definition, scenarios }: LoginDemoProps) {
  const [playbackCycle, setPlaybackCycle] = useState(0);
  const playback = useMemo(() => buildLoginDemoPlayback(definition), [definition, playbackCycle]);
  const [conversationPhase, setConversationPhase] = useState(0);
  const [conversationResetting, setConversationResetting] = useState(false);
  const [questionSubmitted, setQuestionSubmitted] = useState(false);
  const [questionLaunching, setQuestionLaunching] = useState(false);
  const [typedQuestionLength, setTypedQuestionLength] = useState(0);
  const [processExpanded, setProcessExpanded] = useState(false);
  const conversationRef = useRef<HTMLDivElement>(null);
  const selectedScenario = scenarios.find((scenario) => scenario.code === definition.scenarioCode) || scenarios[0];
  const scenarioName = selectedScenario?.name || definition.scenarioName;
  const conversationComplete = conversationPhase === playback.phases.length - 1;
  const conversationHasResult = conversationPhase >= playback.phases.length - 2;
  const typedQuestion = definition.question.slice(0, typedQuestionLength);
  const demoInputStyle = {
    '--ask-scenario-accent': selectedScenario?.color || '#dc2626',
  } as CSSProperties;

  useEffect(() => {
    if (questionSubmitted || questionLaunching || conversationResetting) {
      return;
    }
    const typing = typedQuestionLength < definition.question.length;
    const timer = window.setTimeout(() => {
      if (typing) {
        setTypedQuestionLength((length) => length + 1);
      } else {
        setQuestionLaunching(true);
      }
    }, typing ? definition.timings.typingIntervalMs : definition.timings.submitDelayMs);
    return () => window.clearTimeout(timer);
  }, [conversationResetting, definition, questionLaunching, questionSubmitted, typedQuestionLength]);

  useEffect(() => {
    if (!questionLaunching) {
      return;
    }
    const timer = window.setTimeout(() => {
      setQuestionLaunching(false);
      setConversationPhase(1);
      setQuestionSubmitted(true);
    }, definition.timings.launchDurationMs);
    return () => window.clearTimeout(timer);
  }, [definition.timings.launchDurationMs, questionLaunching]);

  useEffect(() => {
    if (conversationResetting) {
      const timer = window.setTimeout(() => {
        conversationRef.current?.scrollTo({ top: 0, behavior: 'auto' });
        setConversationPhase(0);
        setTypedQuestionLength(0);
        setQuestionSubmitted(false);
        setQuestionLaunching(false);
        setProcessExpanded(false);
        setPlaybackCycle((cycle) => cycle + 1);
        setConversationResetting(false);
      }, definition.timings.resetDelayMs);
      return () => window.clearTimeout(timer);
    }
    if (!questionSubmitted) {
      return;
    }
    const timer = window.setTimeout(() => {
      if (conversationPhase === playback.phases.length - 1) {
        setConversationResetting(true);
      } else {
        setConversationPhase((phase) => phase + 1);
      }
    }, playback.delays[conversationPhase]);
    return () => window.clearTimeout(timer);
  }, [conversationPhase, conversationResetting, definition.timings.resetDelayMs, playback, questionSubmitted]);

  useEffect(() => {
    const frame = window.requestAnimationFrame(() => {
      const container = conversationRef.current;
      if (!container) {
        return;
      }
      if (!questionSubmitted && !conversationResetting) {
        container.scrollTo({ top: 0, behavior: 'auto' });
        return;
      }
      container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [conversationPhase, conversationResetting, processExpanded, questionSubmitted]);

  return (
    <div className="login-agent-demo" aria-label="灵析问答示例" ref={conversationRef}>
      {(!questionSubmitted || questionLaunching) && (
        <LoginDemoComposer
          definition={definition}
          question={typedQuestion}
          scenario={selectedScenario}
          scenarioName={scenarioName}
          style={demoInputStyle}
          launching={questionLaunching}
        />
      )}
      {questionSubmitted && (
        <>
          <div className={conversationPhase === 1 && !conversationResetting
            ? 'run-user-row login-agent-demo-question login-agent-demo-question-active'
            : 'run-user-row login-agent-demo-question'}>
            <div className="run-user-message">
              <div className="run-message-meta"><span>我</span></div>
              <div className="run-user-bubble">
                <div className="run-user-text">{definition.question}</div>
              </div>
            </div>
          </div>
          <div className={conversationPhase === 0
            ? 'login-agent-demo-output login-agent-demo-output-waiting'
            : 'login-agent-demo-output'}>
            <div className="run-agent-row">
              <div className="run-agent-content">
                <div className={conversationComplete
                  ? 'login-agent-demo-live-process login-agent-demo-live-process-collapsed'
                  : 'login-agent-demo-live-process'}>
                  <div className="run-agent-process">
                    <AgentOutput
                      emptyText=""
                      events={playback.phases[conversationPhase]}
                      running={conversationPhase > 0 && !conversationHasResult}
                    />
                  </div>
                </div>
                <div className={conversationComplete
                  ? 'login-agent-demo-disclosure-slot login-agent-demo-disclosure-slot-visible'
                  : 'login-agent-demo-disclosure-slot'}>
                  <section className={processExpanded ? 'run-process-disclosure run-process-disclosure-open' : 'run-process-disclosure'}>
                    <button
                      type="button"
                      className="run-process-disclosure-toggle"
                      aria-expanded={processExpanded}
                      onClick={() => setProcessExpanded((value) => !value)}
                    >
                      <span>{definition.processedLabel}</span>
                      <CaretRight size={14} weight="bold" aria-hidden />
                    </button>
                    <div className="run-process-disclosure-motion" aria-hidden={!processExpanded}>
                      <div className="run-process-disclosure-body run-agent-process">
                        <AgentOutput
                          emptyText={definition.emptyProcessText}
                          events={playback.phases[conversationPhase]}
                        />
                      </div>
                    </div>
                  </section>
                </div>
                {conversationHasResult && (
                  <div className={conversationComplete
                    ? 'run-agent-result login-agent-demo-result'
                    : 'run-agent-result run-agent-result-separated login-agent-demo-result'}>
                    {definition.result}
                  </div>
                )}
              </div>
            </div>
          </div>
          {conversationResetting && (
            <div className="login-agent-demo-next-composer" aria-hidden="true">
              <LoginDemoComposer
                definition={definition}
                question=""
                scenario={selectedScenario}
                scenarioName={scenarioName}
                style={demoInputStyle}
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}

function LoginDemoComposer({ definition, question, scenario, scenarioName, style, launching = false }: {
  definition: LoginDemoDefinition;
  question: string;
  scenario?: PublicScenario;
  scenarioName: string;
  style: CSSProperties;
  launching?: boolean;
}) {
  return (
    <div
      className={launching
        ? 'ask-search-shell login-agent-demo-composer login-agent-demo-composer-launching'
        : 'ask-search-shell login-agent-demo-composer'}
      style={style}
    >
      <div className="ask-input-area">
        <Input.TextArea
          className="ask-search-input ask-search-textarea"
          value={question}
          placeholder={definition.inputPlaceholder}
          readOnly
          autoFocus
          autoSize={false}
          aria-label="问题输入"
        />
        <div className="login-agent-demo-typing-overlay" aria-hidden="true">
          {question}<span className="login-agent-demo-typing-caret" />
        </div>
        <div className="ask-input-footer">
          <div className="ask-footer-left">
            <button type="button" className="ask-scenario-trigger" tabIndex={-1}>
              <span className="ask-scenario-selection">
                <DefinitionIcon src={scenario?.iconUrl} label={scenarioName} size={18} />
                <span className="ask-scenario-selection-text ask-scenario-selection-premise">{definition.premiseName}</span>
                <span className="ask-scenario-selection-separator">·</span>
                <span className="ask-scenario-selection-text">{scenarioName}</span>
              </span>
            </button>
            <button type="button" className="ask-scenario-trigger ask-runtime-trigger" tabIndex={-1}>
              <span className="ask-runtime-selection">
                <span className="ask-runtime-selection-part">
                  <RuntimeIcon iconUrl={definition.runtime.iconUrl} size={15} />
                  <span className="ask-runtime-selection-text">{definition.runtime.name}</span>
                </span>
                <span className="ask-runtime-selection-separator">·</span>
                <span className="ask-runtime-selection-part">
                  <ProviderTypeIcon
                    icon={definition.model.iconUrl}
                    darkIcon={definition.model.darkIconUrl}
                    size={15}
                  />
                  <span className="ask-runtime-selection-text">{definition.model.name}</span>
                </span>
              </span>
            </button>
          </div>
          <div className="ask-input-actions">
            <button type="button" className="ask-tool-button" tabIndex={-1} aria-label="高级参数">
              <SlidersHorizontal size={16} weight="bold" />
            </button>
            <button type="button" className="ask-tool-button" tabIndex={-1} aria-label="上传图片或视频">
              <Paperclip size={16} weight="bold" />
            </button>
            <button type="button" className="ask-tool-button" tabIndex={-1} aria-label="展开输入框">
              <ArrowsOutSimple size={16} weight="bold" />
            </button>
            <button
              type="button"
              className={launching ? 'ask-submit-button ask-submit-button-launching' : 'ask-submit-button'}
              disabled={!question}
              tabIndex={-1}
              aria-label="发起分析"
            >
              <MagnifyingGlass size={18} />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function buildLoginDemoPlayback(definition: LoginDemoDefinition): LoginDemoPlayback {
  const playbackStartedAtMs = Date.now();
  const firstStepStartedAtMs = playbackStartedAtMs
    + definition.question.length * definition.timings.typingIntervalMs
    + definition.timings.submitDelayMs
    + definition.timings.launchDurationMs
    + definition.timings.thinkingDelayMs
    + definition.timings.messageDelayMs;
  const agentMessage: TaskEventItem = {
    id: 2,
    type: 'AGENT_MESSAGE',
    status: 'SUCCESS',
    title: definition.agentMessage,
    createdAt: new Date(playbackStartedAtMs).toISOString(),
  };
  const phases: TaskEventItem[][] = [
    [],
    [{
      id: 1,
      type: 'THINKING',
      status: 'RUNNING',
      title: definition.thinkingTitle,
      createdAt: new Date(playbackStartedAtMs).toISOString(),
    }],
    [agentMessage],
  ];
  const completedEvents: TaskEventItem[] = [];

  definition.steps.forEach((step, index) => {
    const startedAtMs = firstStepStartedAtMs + index * definition.timings.stepDelayMs;
    const running = loginActionEvent(index * 2 + 3, 'RUNNING', index, step, startedAtMs);
    phases.push([agentMessage, ...completedEvents, running]);
    completedEvents.push(running, loginActionEvent(
      index * 2 + 4,
      'SUCCESS',
      index,
      step,
      startedAtMs + definition.timings.stepDelayMs,
    ));
  });

  const completedPhase = [agentMessage, ...completedEvents];
  phases.push(completedPhase, completedPhase);

  return {
    phases,
    delays: [
      0,
      definition.timings.thinkingDelayMs,
      definition.timings.messageDelayMs,
      ...definition.steps.map(() => definition.timings.stepDelayMs),
      definition.timings.resultDelayMs,
      definition.timings.completedDelayMs,
    ],
  };
}

function loginActionEvent(
  id: number,
  status: TaskEventItem['status'],
  stepIndex: number,
  step: LoginDemoDefinition['steps'][number],
  createdAtMs: number,
): TaskEventItem {
  const actionInstanceId = `login-demo-step-${stepIndex + 1}`;
  return {
    id,
    type: 'COMMAND',
    status,
    title: step.label,
    createdAt: new Date(createdAtMs).toISOString(),
    payload: {
      actionKey: `tool:${actionInstanceId}`,
      actionInstanceId,
      actionLabel: step.label,
      actionIcon: step.icon,
    },
  };
}
