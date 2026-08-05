import { describe, expect, it, vi } from 'vitest'
import { ApiError } from './api/client'
import { createHomeOpsQueryClient } from './queryClient'

describe('HomeOps QueryClient', () => {
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
})
