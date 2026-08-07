import { useEffect, useState } from 'react';
import { getAgentRuntimes } from '../api/lingxi';
import type { AgentRuntimeDescriptor } from '../types/api';

const RUNTIME_MODE_CACHE_TTL = 30_000;
let runtimeModeCache: AgentRuntimeDescriptor[] | undefined;
let runtimeModeCacheTime = 0;
let runtimeModeRequest: Promise<AgentRuntimeDescriptor[]> | undefined;

export function invalidateRuntimeModes() {
  runtimeModeCache = undefined;
  runtimeModeCacheTime = 0;
  runtimeModeRequest = undefined;
}

export function useRuntimeModes() {
  const [runtimeModes, setRuntimeModes] = useState<AgentRuntimeDescriptor[]>(() => runtimeModeCache || []);

  useEffect(() => {
    let active = true;
    if (runtimeModeCache && Date.now() - runtimeModeCacheTime < RUNTIME_MODE_CACHE_TTL) {
      return;
    }
    runtimeModeRequest ||= getAgentRuntimes().then((result) => {
      runtimeModeCache = result;
      runtimeModeCacheTime = Date.now();
      return result;
    }).finally(() => {
      runtimeModeRequest = undefined;
    });
    void runtimeModeRequest.then(
      (result) => {
        if (active) {
          setRuntimeModes(result);
        }
      },
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, []);

  return runtimeModes;
}
