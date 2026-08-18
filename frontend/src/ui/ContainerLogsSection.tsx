import { useQueryClient, type QueryKey } from '@tanstack/react-query'
import { FileText, RefreshCw } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, getContainerLogs, isConnectionError } from '../api/client'
import {
  containerLogTails,
  type ContainerLogResponse,
  type ContainerLogTail,
} from '../api/types'
import { formatTimestamp } from '../utils/format'
import { Card } from './Card'

const DEFAULT_TAIL: ContainerLogTail = 100

type LogsState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'success'; response: ContainerLogResponse }
  | { kind: 'error'; error: unknown }

const IDLE_STATE: LogsState = { kind: 'idle' }

interface ContainerLogsSectionProps {
  containerId: string
  supportsContainerLogs: boolean
  logsAllowed: boolean
  stale: boolean
}

export function ContainerLogsSection({
  containerId,
  supportsContainerLogs,
  logsAllowed,
  stale,
}: ContainerLogsSectionProps) {
  const queryClient = useQueryClient()
  const [selectedTail, setSelectedTail] = useState<ContainerLogTail>(DEFAULT_TAIL)
  const [logsState, setLogsState] = useState<LogsState>(IDLE_STATE)
  const generationRef = useRef(0)
  const activeQueryKeyRef = useRef<QueryKey | null>(null)
  const eligible = supportsContainerLogs && logsAllowed && !stale
  const authorityKey = `${containerId}:${supportsContainerLogs}:${logsAllowed}:${stale}`
  const authorityKeyRef = useRef(authorityKey)
  authorityKeyRef.current = authorityKey

  const cancelActiveRequest = useCallback(() => {
    generationRef.current += 1
    const activeQueryKey = activeQueryKeyRef.current
    activeQueryKeyRef.current = null
    if (activeQueryKey !== null) {
      void queryClient.cancelQueries({ queryKey: activeQueryKey, exact: true })
      queryClient.removeQueries({ queryKey: activeQueryKey, exact: true })
    }
  }, [queryClient])

  const discardLogs = useCallback(() => {
    cancelActiveRequest()
    setLogsState(IDLE_STATE)
  }, [cancelActiveRequest])

  useEffect(() => {
    setSelectedTail(DEFAULT_TAIL)
    discardLogs()
  }, [containerId, discardLogs])

  useEffect(() => {
    if (!eligible) {
      discardLogs()
    }
  }, [eligible, discardLogs])

  useEffect(() => () => cancelActiveRequest(), [cancelActiveRequest])

  function selectTail(tail: ContainerLogTail) {
    if (tail === selectedTail) {
      return
    }
    discardLogs()
    setSelectedTail(tail)
  }

  async function loadLogs() {
    if (!eligible || logsState.kind === 'loading') {
      return
    }
    cancelActiveRequest()
    const generation = generationRef.current
    const requestedContainerId = containerId
    const requestedTail = selectedTail
    const requestedAuthority = authorityKeyRef.current
    const queryKey = [
      'container-logs',
      requestedContainerId,
      requestedTail,
      generation,
    ] as const
    activeQueryKeyRef.current = queryKey
    setLogsState({ kind: 'loading' })

    try {
      const response = await queryClient.fetchQuery({
        queryKey,
        queryFn: ({ signal }) => getContainerLogs(
          requestedContainerId,
          requestedTail,
          signal,
        ),
        retry: false,
        gcTime: 0,
        staleTime: 0,
      })
      if (generationRef.current !== generation
          || authorityKeyRef.current !== requestedAuthority) {
        return
      }
      if (response.containerId !== requestedContainerId
          || response.requestedTail !== requestedTail) {
        setLogsState({
          kind: 'error',
          error: new Error('Container log response did not match the request.'),
        })
        return
      }
      setLogsState({ kind: 'success', response })
    } catch (error) {
      if (generationRef.current !== generation
          || authorityKeyRef.current !== requestedAuthority) {
        return
      }
      setLogsState({ kind: 'error', error })
    } finally {
      queryClient.removeQueries({ queryKey, exact: true })
      if (activeQueryKeyRef.current === queryKey) {
        activeQueryKeyRef.current = null
      }
    }
  }

  return (
    <Card>
      <div className="flex items-start gap-3">
        <FileText aria-hidden="true" className="mt-0.5 shrink-0 text-teal-300" size={20} />
        <div className="min-w-0">
          <h3 className="text-base font-semibold">Logs</h3>
          <p className="mt-1 text-sm text-slate-400">
            Load a bounded, one-time tail from the latest eligible container.
          </p>
        </div>
      </div>

      {!supportsContainerLogs ? (
        <UnavailableMessage>
          Container logs are unavailable because the connected Agent does not support this capability.
        </UnavailableMessage>
      ) : stale ? (
        <UnavailableMessage>
          Agent snapshot freshness cannot be confirmed. Logs cannot be requested.
        </UnavailableMessage>
      ) : !logsAllowed ? (
        <UnavailableMessage>Logs are not enabled for this container.</UnavailableMessage>
      ) : (
        <div className="mt-4 space-y-4">
          <fieldset>
            <legend className="text-xs font-semibold uppercase tracking-[0.14em] text-slate-500">
              Tail lines
            </legend>
            <div className="mt-2 grid grid-cols-3 gap-2">
              {containerLogTails.map((tail) => (
                <button
                  key={tail}
                  type="button"
                  aria-pressed={selectedTail === tail}
                  className={`min-h-11 rounded-xl px-3 text-sm font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-300 ${
                    selectedTail === tail
                      ? 'bg-teal-400/15 text-teal-100 ring-1 ring-teal-300/40'
                      : 'bg-white/5 text-slate-300 hover:bg-white/10'
                  }`}
                  onClick={() => selectTail(tail)}
                >
                  {tail}
                </button>
              ))}
            </div>
          </fieldset>

          <button
            type="button"
            disabled={logsState.kind === 'loading'}
            className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-teal-400/15 px-4 text-sm font-semibold text-teal-100 transition hover:bg-teal-400/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-300 disabled:cursor-not-allowed disabled:opacity-60"
            onClick={() => void loadLogs()}
          >
            <RefreshCw
              aria-hidden="true"
              size={17}
              className={logsState.kind === 'loading' ? 'animate-spin' : ''}
            />
            {logsState.kind === 'loading'
              ? 'Loading logs…'
              : logsState.kind === 'success'
                ? 'Refresh logs'
                : 'Load logs'}
          </button>

          <LogsResult state={logsState} />
        </div>
      )}

      <p className="mt-4 text-xs leading-5 text-amber-100/80">
        Sensitive-looking patterns are redacted, but logs may still contain sensitive data.
      </p>
    </Card>
  )
}

function UnavailableMessage({ children }: { children: string }) {
  return (
    <p className="mt-4 rounded-xl bg-amber-400/10 px-3 py-2 text-sm text-amber-100">
      {children}
    </p>
  )
}

function LogsResult({ state }: { state: LogsState }) {
  if (state.kind === 'idle' || state.kind === 'loading') {
    return state.kind === 'loading'
      ? <p role="status" className="text-sm text-slate-400">Waiting for the Agent response…</p>
      : null
  }
  if (state.kind === 'error') {
    return (
      <p role="alert" className="rounded-xl bg-rose-400/10 px-3 py-2 text-sm text-rose-100">
        {logErrorMessage(state.error)}
      </p>
    )
  }

  const response = state.response
  return (
    <section aria-label="Loaded container logs" className="space-y-3">
      <div className="text-xs text-slate-500">
        Collected {formatTimestamp(response.collectedAt)} · {response.lines.length} line{response.lines.length === 1 ? '' : 's'}
      </div>
      {response.redactionApplied && (
        <p role="status" className="rounded-xl bg-amber-400/10 px-3 py-2 text-sm text-amber-100">
          Sensitive-looking values were redacted.
        </p>
      )}
      {response.truncated && (
        <p role="status" className="rounded-xl bg-amber-400/10 px-3 py-2 text-sm text-amber-100">
          Output was truncated to stay within safety limits.
        </p>
      )}
      {response.lines.length === 0 ? (
        <p className="rounded-xl bg-black/20 px-3 py-3 text-sm text-slate-400">
          No recent log lines.
        </p>
      ) : (
        <ol
          aria-label="Container log lines"
          className="max-h-[32rem] space-y-2 overflow-x-hidden overflow-y-auto rounded-xl bg-black/30 p-2"
        >
          {response.lines.map((line, index) => (
            <li key={`${line.timestamp ?? 'none'}-${line.stream}-${index}`} className="min-w-0 rounded-lg bg-slate-950/80 p-3">
              <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-slate-500">
                <span>{line.timestamp ? formatTimestamp(line.timestamp) : 'Timestamp not reported'}</span>
                <span className={streamClass(line.stream)}>{line.stream}</span>
              </div>
              <p className="mt-2 min-w-0 whitespace-pre-wrap break-words font-mono text-xs leading-5 text-slate-200 [overflow-wrap:anywhere]">
                {line.message}
              </p>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

function streamClass(stream: ContainerLogResponse['lines'][number]['stream']) {
  if (stream === 'STDERR') {
    return 'font-semibold text-rose-300'
  }
  if (stream === 'COMBINED') {
    return 'font-semibold text-amber-300'
  }
  return 'font-semibold text-teal-300'
}

function logErrorMessage(error: unknown) {
  if (isConnectionError(error)) {
    return error.message
  }
  if (error instanceof ApiError) {
    switch (error.status) {
      case 400:
        return 'The log request was invalid. Reload the container detail before trying again.'
      case 404:
        return 'This container is no longer reported.'
      case 409:
        return 'The container identifier conflicts with another reported container.'
      case 422:
        return 'Logs are not enabled for this container.'
      case 429:
        return 'Container log requests are busy. Try again shortly.'
      case 503:
        return 'Container log retrieval is temporarily unavailable.'
      case 504:
        return 'The container log request timed out.'
      default:
        return error.message
    }
  }
  return 'Container logs could not be loaded.'
}
