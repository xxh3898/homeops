import { describe, expect, it, vi } from 'vitest'
import { ApiError } from './api/client'
import { createHomeOpsQueryClient } from './queryClient'

describe('HomeOps QueryClient', () => {
  it('retries transient queries once after a fixed one-second delay', () => {
    const queryClient = createHomeOpsQueryClient(vi.fn())
    const options = queryClient.getDefaultOptions().queries

    expect(options?.retry).toBeTypeOf('function')
    expect(options?.retryDelay).toBe(1_000)
  })

  it.each([401, 403])('blocks access and clears all cached data after status %s', async (status) => {
    const onAuthorizationError = vi.fn()
    const queryClient = createHomeOpsQueryClient(onAuthorizationError)
    const error = new ApiError(status, 'access denied')
    queryClient.setQueryData(['system-summary'], { cpuUsagePercent: 12.5 })
    queryClient.setQueryData(['containers'], [{ name: 'homeops-api' }])

    await expect(
      queryClient.fetchQuery({
        queryKey: ['authorization-check'],
        queryFn: () => Promise.reject(error),
        retry: false,
      }),
    ).rejects.toBe(error)

    expect(onAuthorizationError).toHaveBeenCalledOnce()
    expect(onAuthorizationError).toHaveBeenCalledWith(error)
    expect(queryClient.getQueryData(['system-summary'])).toBeUndefined()
    expect(queryClient.getQueryData(['containers'])).toBeUndefined()
  })

  it('preserves cached data after a transient API failure', async () => {
    const onAuthorizationError = vi.fn()
    const queryClient = createHomeOpsQueryClient(onAuthorizationError)
    const cachedSummary = { cpuUsagePercent: 12.5 }
    const error = new ApiError(503, 'temporarily unavailable')
    queryClient.setQueryData(['system-summary'], cachedSummary)

    await expect(
      queryClient.fetchQuery({
        queryKey: ['transient-check'],
        queryFn: () => Promise.reject(error),
        retry: false,
      }),
    ).rejects.toBe(error)

    expect(onAuthorizationError).not.toHaveBeenCalled()
    expect(queryClient.getQueryData(['system-summary'])).toEqual(cachedSummary)
  })

  it.each([401, 403])('blocks access and clears cached data after mutation status %s', async (status) => {
    const onAuthorizationError = vi.fn()
    const queryClient = createHomeOpsQueryClient(onAuthorizationError)
    const error = new ApiError(status, 'access denied')
    queryClient.setQueryData(['container', '0123456789ab'], { state: 'RUNNING' })
    const mutation = queryClient.getMutationCache().build(queryClient, {
      mutationFn: () => Promise.reject(error),
      retry: false,
    })

    await expect(mutation.execute(undefined)).rejects.toBe(error)

    expect(onAuthorizationError).toHaveBeenCalledOnce()
    expect(onAuthorizationError).toHaveBeenCalledWith(error)
    expect(queryClient.getQueryData(['container', '0123456789ab'])).toBeUndefined()
  })
})
