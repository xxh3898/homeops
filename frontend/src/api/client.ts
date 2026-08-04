import type { ContainerInventory, SystemSummary } from './types'

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, {
    method: 'GET',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: {
      Accept: 'application/json',
    },
  })
  if (!response.ok) {
    throw new ApiError(response.status, messageForStatus(response.status))
  }
  return (await response.json()) as T
}

function messageForStatus(status: number) {
  if (status === 401 || status === 403) {
    return 'Tailscale identity is not authorized for HomeOps.'
  }
  if (status >= 500) {
    return 'HomeOps API is temporarily unavailable.'
  }
  return `HomeOps request failed with status ${status}.`
}

export function getSystemSummary() {
  return getJson<SystemSummary>('/api/v1/system/summary')
}

export function getContainers() {
  return getJson<ContainerInventory>('/api/v1/containers')
}
