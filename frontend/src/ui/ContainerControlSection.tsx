import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query'
import { AlertTriangle, Play, RefreshCw, RotateCw, ShieldCheck, Square } from 'lucide-react'
import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
} from 'react'
import {
  ApiError,
  getContainerAction,
  getSessionCsrfToken,
  isAmbiguousContainerActionSubmissionError,
  isConnectionError,
  shouldRetryQuery,
  submitContainerAction,
} from '../api/client'
import type {
  ContainerActionResponse,
  ContainerControlOperation,
  ContainerView,
} from '../api/types'
import { formatTimestamp } from '../utils/format'
import { Card } from './Card'

export const CONTAINER_ACTION_POLL_INTERVAL_MS = 1_500
export const CONTAINER_ACTION_POLL_MAX_MS = 60_000

const canonicalUuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

interface ContainerControlSectionProps {
  container: ContainerView
  snapshotUpdatedAt: string
  stale: boolean
  online: boolean
  visible: boolean
}

interface SubmissionTuple {
  containerId: string
  operation: ContainerControlOperation
  idempotencyKey: string
}

type ControlState =
  | { kind: 'idle' }
  | { kind: 'submitting'; operation: ContainerControlOperation; retry: boolean }
  | { kind: 'submission-unknown'; tuple: SubmissionTuple }
  | {
      kind: 'requested'
      response: ContainerActionResponse
      deadlineAt: number
      automatic: boolean
      refreshing: boolean
      pollError: string | null
    }
  | {
      kind: 'terminal'
      response: ContainerActionResponse
      snapshotBarrierAtTerminal: string
      freshSnapshotObserved: boolean
    }
  | { kind: 'error'; message: string }

const IDLE_STATE: ControlState = { kind: 'idle' }

const actions: Array<{
  operation: ContainerControlOperation
  label: string
  warning: string
  icon: typeof Play
}> = [
  {
    operation: 'START',
    label: 'Start',
    warning: 'Start this container?',
    icon: Play,
  },
  {
    operation: 'STOP',
    label: 'Stop',
    warning: 'Stop this running container? Active service traffic may be interrupted.',
    icon: Square,
  },
  {
    operation: 'RESTART',
    label: 'Restart',
    warning: 'Restart this running container? The service will be briefly unavailable.',
    icon: RotateCw,
  },
]

export function ContainerControlSection({
  container,
  snapshotUpdatedAt,
  stale,
  online,
  visible,
}: ContainerControlSectionProps) {
  const queryClient = useQueryClient()
  const availabilityId = useId()
  const dialogTitleId = useId()
  const dialogDescriptionId = useId()
  const [confirmation, setConfirmation] = useState<ContainerControlOperation | null>(null)
  const [state, setState] = useState<ControlState>(IDLE_STATE)
  const generationRef = useRef(0)
  const sequenceRef = useRef(0)
  const submissionLockRef = useRef(false)
  const refreshLockRef = useRef(false)
  const activeReadKeyRef = useRef<QueryKey | null>(null)
  const activePostControllerRef = useRef<AbortController | null>(null)
  const invokingControlRef = useRef<HTMLButtonElement | null>(null)
  const cancelButtonRef = useRef<HTMLButtonElement | null>(null)
  const previousContainerIdRef = useRef(container.id)
  const latestSnapshotUpdatedAtRef = useRef(snapshotUpdatedAt)
  const decision = controlDecision(container, stale, online)
  const operationActive = state.kind === 'submitting'
    || state.kind === 'submission-unknown'
    || state.kind === 'requested'
    || (state.kind === 'terminal' && !state.freshSnapshotObserved)
  const controlsBusy = confirmation !== null || operationActive

  useLayoutEffect(() => {
    latestSnapshotUpdatedAtRef.current = snapshotUpdatedAt
  }, [snapshotUpdatedAt])

  const submission = useMutation({
    mutationKey: ['container-action-submit', container.id],
    mutationFn: ({ tuple, csrfToken, signal }: {
      tuple: SubmissionTuple
      csrfToken: string
      signal: AbortSignal
    }) => submitContainerAction(
      tuple.containerId,
      tuple.operation,
      tuple.idempotencyKey,
      csrfToken,
      signal,
    ),
    retry: false,
    gcTime: 0,
  })

  function cancelActiveRead() {
    const activeKey = activeReadKeyRef.current
    activeReadKeyRef.current = null
    if (activeKey !== null) {
      void queryClient.cancelQueries({ queryKey: activeKey, exact: true })
      queryClient.removeQueries({ queryKey: activeKey, exact: true })
    }
  }

  function cancelActivePost() {
    activePostControllerRef.current?.abort(
      new DOMException('Container control request cancelled.', 'AbortError'),
    )
    activePostControllerRef.current = null
  }

  function resetForContainerChange() {
    generationRef.current += 1
    submissionLockRef.current = false
    refreshLockRef.current = false
    cancelActiveRead()
    cancelActivePost()
    setConfirmation(null)
    setState(IDLE_STATE)
  }

  useEffect(() => {
    if (previousContainerIdRef.current === container.id) {
      return
    }
    previousContainerIdRef.current = container.id
    resetForContainerChange()
    submission.reset()
  }, [container.id])

  useEffect(() => () => {
    generationRef.current += 1
    cancelActiveRead()
    cancelActivePost()
  }, [])

  useEffect(() => {
    if (confirmation !== null && !decision.allowed.has(confirmation)) {
      setConfirmation(null)
      invokingControlRef.current?.focus()
    }
  }, [confirmation, decision.key])

  useEffect(() => {
    if (confirmation !== null && state.kind !== 'submitting') {
      cancelButtonRef.current?.focus()
    }
  }, [confirmation, state.kind])

  useEffect(() => {
    if (state.kind === 'terminal'
        && !state.freshSnapshotObserved
        && newerSnapshot(snapshotUpdatedAt, state.snapshotBarrierAtTerminal)) {
      setState({ ...state, freshSnapshotObserved: true })
    }
  }, [snapshotUpdatedAt, state])

  useEffect(() => {
    if (state.kind !== 'requested' || !state.automatic) {
      return
    }

    const remaining = state.deadlineAt - Date.now()
    if (remaining <= 0) {
      setState({ ...state, automatic: false, refreshing: false })
      return
    }

    let cancelled = false
    let queryKey: QueryKey | null = null
    const deadlineTimer = setTimeout(() => {
      if (cancelled) return
      cancelled = true
      if (queryKey !== null) {
        void queryClient.cancelQueries({ queryKey, exact: true })
        queryClient.removeQueries({ queryKey, exact: true })
      }
      setState((current) => current.kind === 'requested'
        && current.response.operationId === state.response.operationId
        ? { ...current, automatic: false, refreshing: false }
        : current)
    }, remaining)

    let pollTimer: ReturnType<typeof setTimeout> | undefined
    if (visible && online) {
      pollTimer = setTimeout(() => {
        queryKey = operationQueryKey(state.response.operationId, ++sequenceRef.current)
        activeReadKeyRef.current = queryKey
        void queryClient.fetchQuery({
          queryKey,
          queryFn: ({ signal }) => getContainerAction(state.response.operationId, signal),
          retry: shouldRetryQuery,
          retryDelay: 1_000,
          staleTime: 0,
          gcTime: 0,
        }).then((response) => {
          if (!cancelled) {
            acceptPolledResponse(response, state)
          }
        }).catch((error: unknown) => {
          if (!cancelled && !isAbortError(error)) {
            setState((current) => current.kind === 'requested'
              && current.response.operationId === state.response.operationId
              ? { ...current, pollError: pollingErrorMessage(error), refreshing: false }
              : current)
          }
        }).finally(() => {
          if (queryKey !== null) {
            queryClient.removeQueries({ queryKey, exact: true })
            if (activeReadKeyRef.current === queryKey) {
              activeReadKeyRef.current = null
            }
          }
        })
      }, Math.min(CONTAINER_ACTION_POLL_INTERVAL_MS, remaining))
    }

    return () => {
      cancelled = true
      clearTimeout(deadlineTimer)
      if (pollTimer !== undefined) clearTimeout(pollTimer)
      if (queryKey !== null) {
        void queryClient.cancelQueries({ queryKey, exact: true })
        queryClient.removeQueries({ queryKey, exact: true })
      }
    }
  }, [online, queryClient, state, visible])

  function openConfirmation(
    operation: ContainerControlOperation,
    event: MouseEvent<HTMLButtonElement>,
  ) {
    if (controlsBusy || !decision.allowed.has(operation)) return
    invokingControlRef.current = event.currentTarget
    setConfirmation(operation)
  }

  function closeConfirmation() {
    if (state.kind === 'submitting') return
    const invokingControl = invokingControlRef.current
    setConfirmation(null)
    setTimeout(() => invokingControl?.focus(), 0)
  }

  function handleDialogKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape' && state.kind !== 'submitting') {
      event.preventDefault()
      closeConfirmation()
    }
  }

  async function resolveCsrfToken(generation: number) {
    const queryKey = ['container-control-session', container.id, ++sequenceRef.current] as const
    activeReadKeyRef.current = queryKey
    try {
      return await queryClient.fetchQuery({
        queryKey,
        queryFn: ({ signal }) => getSessionCsrfToken(signal),
        retry: false,
        staleTime: 0,
        gcTime: 0,
      })
    } finally {
      queryClient.removeQueries({ queryKey, exact: true })
      if (generationRef.current === generation && activeReadKeyRef.current === queryKey) {
        activeReadKeyRef.current = null
      }
    }
  }

  async function confirmOperation() {
    if (confirmation === null
        || submissionLockRef.current
        || !decision.allowed.has(confirmation)) {
      return
    }
    submissionLockRef.current = true
    const generation = generationRef.current
    const operation = confirmation
    setState({ kind: 'submitting', operation, retry: false })

    let csrfToken: string
    try {
      csrfToken = await resolveCsrfToken(generation)
    } catch (error) {
      if (generationRef.current === generation && !isAbortError(error)) {
        setConfirmation(null)
        setState({ kind: 'error', message: preSubmissionErrorMessage(error) })
      }
      submissionLockRef.current = false
      return
    }

    if (generationRef.current !== generation) {
      submissionLockRef.current = false
      return
    }

    const idempotencyKey = crypto.randomUUID()
    if (!canonicalUuidPattern.test(idempotencyKey)) {
      setConfirmation(null)
      setState({ kind: 'error', message: 'A safe request identifier could not be created.' })
      submissionLockRef.current = false
      return
    }

    const tuple: SubmissionTuple = {
      containerId: container.id,
      operation,
      idempotencyKey,
    }
    await postTuple(tuple, csrfToken, generation, false)
    submissionLockRef.current = false
  }

  async function retrySameRequest() {
    if (state.kind !== 'submission-unknown' || submissionLockRef.current || !online) {
      return
    }
    submissionLockRef.current = true
    const tuple = state.tuple
    const generation = generationRef.current
    setState({ kind: 'submitting', operation: tuple.operation, retry: true })

    let csrfToken: string
    try {
      csrfToken = await resolveCsrfToken(generation)
    } catch (error) {
      if (generationRef.current === generation && !isAbortError(error)) {
        setState({ kind: 'submission-unknown', tuple })
      }
      submissionLockRef.current = false
      return
    }

    if (generationRef.current === generation) {
      await postTuple(tuple, csrfToken, generation, true)
    }
    submissionLockRef.current = false
  }

  async function postTuple(
    tuple: SubmissionTuple,
    csrfToken: string,
    generation: number,
    retry: boolean,
  ) {
    const controller = new AbortController()
    activePostControllerRef.current = controller
    setState({ kind: 'submitting', operation: tuple.operation, retry })
    try {
      const response = await submission.mutateAsync({ tuple, csrfToken, signal: controller.signal })
      if (generationRef.current !== generation) return
      setConfirmation(null)
      if (response.status === 'REQUESTED') {
        setState({
          kind: 'requested',
          response,
          deadlineAt: Date.now() + CONTAINER_ACTION_POLL_MAX_MS,
          automatic: true,
          refreshing: false,
          pollError: null,
        })
      } else {
        acceptTerminalResponse(response)
      }
    } catch (error) {
      if (generationRef.current !== generation || isAbortError(error)) return
      setConfirmation(null)
      if (isAmbiguousContainerActionSubmissionError(error)) {
        setState({ kind: 'submission-unknown', tuple })
      } else {
        setState({ kind: 'error', message: submissionErrorMessage(error) })
      }
    } finally {
      if (activePostControllerRef.current === controller) {
        activePostControllerRef.current = null
        submission.reset()
      }
    }
  }

  function acceptPolledResponse(
    response: ContainerActionResponse,
    current: Extract<ControlState, { kind: 'requested' }>,
  ) {
    if (response.operationId !== current.response.operationId
        || response.containerId !== current.response.containerId
        || response.operation !== current.response.operation) {
      setState({
        ...current,
        automatic: false,
        refreshing: false,
        pollError: 'HomeOps returned an unexpected action status. Automatic polling stopped.',
      })
      return
    }
    if (response.status === 'REQUESTED') {
      setState({ ...current, response, refreshing: false, pollError: null })
      return
    }
    acceptTerminalResponse(response)
  }

  function acceptTerminalResponse(response: ContainerActionResponse) {
    setState({
      kind: 'terminal',
      response,
      snapshotBarrierAtTerminal: latestSnapshotUpdatedAtRef.current,
      freshSnapshotObserved: false,
    })
    void queryClient.invalidateQueries({ queryKey: ['container', container.id], exact: true })
  }

  async function refreshStatus() {
    if (state.kind !== 'requested' || refreshLockRef.current || !online) return
    refreshLockRef.current = true
    const current = state
    const generation = generationRef.current
    const queryKey = operationQueryKey(current.response.operationId, ++sequenceRef.current)
    activeReadKeyRef.current = queryKey
    setState({ ...current, refreshing: true, pollError: null })
    try {
      const response = await queryClient.fetchQuery({
        queryKey,
        queryFn: ({ signal }) => getContainerAction(current.response.operationId, signal),
        retry: shouldRetryQuery,
        retryDelay: 1_000,
        staleTime: 0,
        gcTime: 0,
      })
      if (generationRef.current === generation) {
        acceptPolledResponse(response, current)
      }
    } catch (error) {
      if (generationRef.current === generation && !isAbortError(error)) {
        setState({ ...current, automatic: false, refreshing: false, pollError: pollingErrorMessage(error) })
      }
    } finally {
      queryClient.removeQueries({ queryKey, exact: true })
      if (activeReadKeyRef.current === queryKey) activeReadKeyRef.current = null
      refreshLockRef.current = false
    }
  }

  const availabilityMessage = controlsBusy
    ? activeAvailabilityMessage(state)
    : decision.message

  return (
    <Card className="overflow-hidden">
      <div className="flex items-start gap-3">
        <ShieldCheck aria-hidden="true" className="mt-0.5 shrink-0 text-teal-300" size={20} />
        <div className="min-w-0">
          <h3 className="text-base font-semibold">Container control</h3>
          <p className="mt-1 text-sm text-slate-400">
            Candidate actions use the latest snapshot. HomeOps and the Agent perform final live authorization.
          </p>
        </div>
      </div>

      <div className="mt-4 grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-3">
        {actions.map(({ operation, label, icon: Icon }) => {
          const enabled = !controlsBusy && decision.allowed.has(operation)
          return (
            <button
              key={operation}
              type="button"
              disabled={!enabled}
              aria-describedby={availabilityId}
              className={actionClass(operation)}
              onClick={(event) => openConfirmation(operation, event)}
            >
              <Icon aria-hidden="true" size={17} /> {label}
            </button>
          )
        })}
      </div>

      <p id={availabilityId} className="mt-3 break-words text-sm text-slate-400">
        {availabilityMessage}
      </p>

      <ControlResult
        state={state}
        online={online}
        visible={visible}
        onRetry={() => void retrySameRequest()}
        onRefresh={() => void refreshStatus()}
        onDismiss={() => setState(IDLE_STATE)}
      />

      {confirmation !== null && (
        <div
          className="fixed inset-0 z-50 flex items-end justify-center overflow-y-auto bg-slate-950/80 p-4 pb-[calc(1rem+env(safe-area-inset-bottom))] pt-[calc(1rem+env(safe-area-inset-top))] backdrop-blur-sm sm:items-center"
          onKeyDown={handleDialogKeyDown}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby={dialogTitleId}
            aria-describedby={dialogDescriptionId}
            className="max-h-full w-full min-w-0 max-w-md overflow-y-auto rounded-2xl border border-white/15 bg-slate-900 p-5 shadow-2xl"
          >
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-amber-300">
              Confirm container action
            </p>
            <h4 id={dialogTitleId} className="mt-2 break-words text-lg font-semibold">
              {actions.find((action) => action.operation === confirmation)?.warning}
            </h4>
            <p id={dialogDescriptionId} className="mt-3 break-words text-sm leading-6 text-slate-300">
              <span className="font-semibold text-slate-100">{container.name}</span>
              {' · '}
              <span className="break-all font-mono">{container.id}</span>
              <br />
              Final Backend and Agent checks may still deny this candidate action.
            </p>
            <div className="mt-5 grid grid-cols-1 gap-2 sm:grid-cols-2">
              <button
                ref={cancelButtonRef}
                type="button"
                disabled={state.kind === 'submitting'}
                className="min-h-11 rounded-xl bg-white/5 px-4 text-sm font-semibold text-slate-200 transition hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-300 disabled:cursor-not-allowed disabled:opacity-60"
                onClick={closeConfirmation}
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={state.kind === 'submitting'}
                className={confirmClass(confirmation)}
                onClick={() => void confirmOperation()}
              >
                {state.kind === 'submitting' ? 'Submitting…' : `Confirm ${confirmation.toLowerCase()}`}
              </button>
            </div>
          </div>
        </div>
      )}
    </Card>
  )
}

function ControlResult({
  state,
  online,
  visible,
  onRetry,
  onRefresh,
  onDismiss,
}: {
  state: ControlState
  online: boolean
  visible: boolean
  onRetry: () => void
  onRefresh: () => void
  onDismiss: () => void
}) {
  if (state.kind === 'idle') return null
  if (state.kind === 'submitting') {
    return (
      <p role="status" className="mt-4 rounded-xl bg-teal-400/10 px-3 py-3 text-sm text-teal-100">
        {state.retry ? 'Retrying the same request…' : 'Submitting the confirmed action…'}
      </p>
    )
  }
  if (state.kind === 'submission-unknown') {
    return (
      <section role="alert" className="mt-4 space-y-3 rounded-xl border border-amber-400/30 bg-amber-400/10 p-3">
        <div className="flex items-start gap-2">
          <AlertTriangle aria-hidden="true" className="mt-0.5 shrink-0 text-amber-300" size={18} />
          <div className="min-w-0">
            <h4 className="font-semibold text-amber-100">Submission status unknown</h4>
            <p className="mt-1 text-sm leading-5 text-amber-50/80">
              HomeOps may already have received this request. Retry only with the same in-memory request, or inspect fresh container state before another action.
            </p>
          </div>
        </div>
        <button
          type="button"
          disabled={!online}
          className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-amber-300/15 px-4 text-sm font-semibold text-amber-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-300 disabled:cursor-not-allowed disabled:opacity-60"
          onClick={onRetry}
        >
          <RefreshCw aria-hidden="true" size={17} /> Retry same request
        </button>
        <p className="text-xs leading-5 text-amber-50/70">
          Leaving or reloading this page discards the in-memory retry key and never replays it in the background.
        </p>
      </section>
    )
  }
  if (state.kind === 'requested') {
    return (
      <section aria-label="Current container action" className="mt-4 space-y-3 rounded-xl bg-sky-400/10 p-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h4 className="font-semibold text-sky-100">Action requested</h4>
          <span className="rounded-full bg-sky-300/10 px-2 py-1 text-xs font-semibold text-sky-200">REQUESTED</span>
        </div>
        <p className="text-sm text-slate-300">
          {state.response.operation} was durably accepted. Waiting for a bounded Agent result.
        </p>
        {!online ? (
          <p role="status" className="text-sm text-amber-100">Status polling is paused while offline.</p>
        ) : !visible ? (
          <p role="status" className="text-sm text-amber-100">Status polling is paused while this page is hidden.</p>
        ) : state.automatic ? (
          <p role="status" className="text-sm text-slate-400">Checking status automatically…</p>
        ) : (
          <button
            type="button"
            disabled={state.refreshing || !online}
            className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-sky-300/15 px-4 text-sm font-semibold text-sky-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-300 disabled:cursor-not-allowed disabled:opacity-60"
            onClick={onRefresh}
          >
            <RefreshCw aria-hidden="true" size={17} className={state.refreshing ? 'animate-spin' : ''} />
            {state.refreshing ? 'Refreshing status…' : 'Refresh status'}
          </button>
        )}
        {state.pollError && <p role="alert" className="text-sm text-rose-200">{state.pollError}</p>}
      </section>
    )
  }
  if (state.kind === 'error') {
    return (
      <section role="alert" className="mt-4 space-y-3 rounded-xl bg-rose-400/10 p-3">
        <h4 className="font-semibold text-rose-100">Container action not submitted</h4>
        <p className="text-sm text-rose-50/80">{state.message}</p>
        <button
          type="button"
          className="min-h-11 rounded-xl bg-white/5 px-4 text-sm font-semibold text-slate-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-300"
          onClick={onDismiss}
        >
          Dismiss
        </button>
      </section>
    )
  }

  const response = state.response
  return (
    <section aria-label="Last container action" className="mt-4 space-y-3 rounded-xl bg-black/20 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="font-semibold text-slate-100">Last action</h4>
        <span className="rounded-full bg-white/5 px-2 py-1 text-xs font-semibold text-slate-200">
          {response.status}
        </span>
      </div>
      <dl className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-xs text-slate-500">Operation</dt>
          <dd className="mt-1 text-slate-200">{response.operation}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Completed</dt>
          <dd className="mt-1 break-words text-slate-200">
            {response.completedAt ? formatTimestamp(response.completedAt) : 'Not reported'}
          </dd>
        </div>
      </dl>
      <p className="break-words text-sm leading-6 text-slate-300">
        {terminalMessage(response)}
      </p>
      {!state.freshSnapshotObserved && (
        <p role="status" className="text-sm text-amber-100">
          Waiting for a newer Agent snapshot before another control action is available.
        </p>
      )}
    </section>
  )
}

function controlDecision(container: ContainerView, stale: boolean, online: boolean) {
  let allowed: ReadonlySet<ContainerControlOperation> = new Set()
  let message: string
  if (!online) {
    message = 'Controls are unavailable while this browser is offline.'
  } else if (stale) {
    message = 'Controls require a fresh container snapshot without a background refresh error.'
  } else if (!container.managed) {
    message = 'This container is read-only because the exact managed inventory label is not reported.'
  } else if (!eligibleProject(container.composeProject)) {
    message = 'Controls require a reported non-HomeOps Compose project. Server allowlist checks still apply.'
  } else {
    switch (container.state.trim().toUpperCase()) {
      case 'RUNNING':
        allowed = new Set(['STOP', 'RESTART'])
        message = 'The latest snapshot allows Stop or Restart as candidates.'
        break
      case 'EXITED':
      case 'CREATED':
        allowed = new Set(['START'])
        message = 'The latest snapshot allows Start as a candidate.'
        break
      default:
        message = `No control candidate is available for the reported ${safeState(container.state)} state.`
    }
  }
  return {
    allowed,
    message,
    key: `${container.id}:${container.managed}:${container.composeProject ?? ''}:${container.state}:${stale}:${online}`,
  }
}

function eligibleProject(project: string | null) {
  const normalized = project?.trim().toLowerCase()
  return normalized !== undefined
    && normalized.length > 0
    && normalized !== 'unknown'
    && normalized !== 'homeops'
}

function safeState(state: string) {
  const normalized = state.trim().toUpperCase()
  return /^[A-Z_]{1,32}$/.test(normalized) ? normalized : 'UNKNOWN'
}

function activeAvailabilityMessage(state: ControlState) {
  switch (state.kind) {
    case 'submitting':
      return 'A confirmed container action is being submitted.'
    case 'submission-unknown':
      return 'Resolve the unknown submission before creating another action.'
    case 'requested':
      return 'Wait for the current operation to become terminal before creating another action.'
    case 'terminal':
      return state.freshSnapshotObserved
        ? 'A newer Agent snapshot is available for the next candidate action.'
        : 'Wait for a newer Agent snapshot before creating another action.'
    default:
      return 'Finish or cancel the current confirmation before selecting another action.'
  }
}

function actionClass(operation: ContainerControlOperation) {
  const tone = operation === 'START'
    ? 'bg-teal-400/15 text-teal-100 hover:bg-teal-400/20 focus-visible:ring-teal-300'
    : operation === 'STOP'
      ? 'bg-rose-400/15 text-rose-100 hover:bg-rose-400/20 focus-visible:ring-rose-300'
      : 'bg-amber-400/15 text-amber-100 hover:bg-amber-400/20 focus-visible:ring-amber-300'
  return `inline-flex min-h-11 min-w-0 items-center justify-center gap-2 rounded-xl px-3 text-sm font-semibold transition focus-visible:outline-none focus-visible:ring-2 disabled:cursor-not-allowed disabled:bg-white/5 disabled:text-slate-600 ${tone}`
}

function confirmClass(operation: ContainerControlOperation) {
  const tone = operation === 'START'
    ? 'bg-teal-400/20 text-teal-100 focus-visible:ring-teal-300'
    : operation === 'STOP'
      ? 'bg-rose-400/20 text-rose-100 focus-visible:ring-rose-300'
      : 'bg-amber-400/20 text-amber-100 focus-visible:ring-amber-300'
  return `min-h-11 rounded-xl px-4 text-sm font-semibold focus-visible:outline-none focus-visible:ring-2 disabled:cursor-not-allowed disabled:opacity-60 ${tone}`
}

function operationQueryKey(operationId: string, sequence: number) {
  return ['container-action', operationId, sequence] as const
}

function newerSnapshot(candidate: string, baseline: string) {
  const candidateTime = Date.parse(candidate)
  const baselineTime = Date.parse(baseline)
  return Number.isFinite(candidateTime)
    && Number.isFinite(baselineTime)
    && candidateTime > baselineTime
}

function preSubmissionErrorMessage(error: unknown) {
  if (isConnectionError(error)) {
    return 'The security session could not be refreshed. No container action was submitted.'
  }
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
    return 'The current security session is not authorized. No container action was submitted.'
  }
  return 'The security session could not be prepared. No container action was submitted.'
}

function submissionErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    switch (error.status) {
      case 400:
        return 'The fixed container action request was rejected as invalid.'
      case 401:
      case 403:
        return 'The current authorization or security session was rejected.'
      case 404:
        return 'The target container is no longer reported.'
      case 409:
        return 'Another control operation is active or the request conflicts with an existing operation.'
      case 422:
        return 'The target is no longer eligible or the confirmation was rejected.'
      case 429:
        return 'Too many new container actions were requested. No automatic retry will occur.'
      default:
        return 'The container action was rejected without exposing private server details.'
    }
  }
  return 'The container action could not be submitted.'
}

function pollingErrorMessage(error: unknown) {
  if (isConnectionError(error)) {
    return 'Container action status is temporarily unreachable. Automatic GET polling remains bounded.'
  }
  if (error instanceof ApiError) {
    if (error.status === 401 || error.status === 403) {
      return 'The current security session cannot read this action status.'
    }
    if (error.status === 404) {
      return 'The action status is no longer available. Use manual refresh only after checking the current container state.'
    }
  }
  return 'Container action status could not be confirmed. Automatic GET polling remains bounded.'
}

function terminalMessage(response: ContainerActionResponse) {
  switch (response.status) {
    case 'APPLIED':
      return 'The Docker operation was applied. The visible container state changes only after a fresh Agent snapshot reports it.'
    case 'NOOP':
      return response.reasonCode === 'ALREADY_RUNNING'
        ? 'No operation was needed because the container was already running.'
        : response.reasonCode === 'ALREADY_STOPPED'
          ? 'No operation was needed because the container was already stopped.'
          : 'No operation was needed because the target was already in the requested state.'
    case 'DENIED':
      return deniedMessage(response.reasonCode)
    case 'FAILED':
      return 'The Agent reported a definite execution failure. Private Docker error details are not exposed.'
    case 'EXPIRED':
      return 'The bounded work expired before it could be executed safely.'
    case 'OUTCOME_UNKNOWN':
      return 'The operation outcome is uncertain. Do not retry automatically; inspect a fresh container snapshot before another action.'
    default:
      return 'The action has not reached a terminal result.'
  }
}

function deniedMessage(reasonCode: string | null) {
  switch (reasonCode) {
    case 'NOT_MANAGED':
    case 'PROJECT_MISMATCH':
    case 'PROTECTED_PROJECT':
      return 'The current control policy denied this container or project.'
    case 'PROTECTED_SERVICE':
    case 'WRITABLE_MOUNT':
    case 'MOUNT_PROTECTION_UNAVAILABLE':
      return 'Live Agent protection denied this stateful or protected target.'
    case 'CONTAINER_NOT_FOUND':
    case 'AMBIGUOUS_IDENTIFIER':
      return 'Live container identity verification did not select exactly one safe target.'
    default:
      return 'The current Backend or Agent control policy denied this action.'
  }
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}
