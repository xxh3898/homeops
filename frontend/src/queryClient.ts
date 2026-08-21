import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { type ApiError, isAuthorizationError, shouldRetryQuery } from './api/client'

export function createHomeOpsQueryClient(onAuthorizationError: (error: ApiError) => void) {
  let queryClient: QueryClient
  const handleError = (error: unknown) => {
    if (isAuthorizationError(error)) {
      onAuthorizationError(error)
      queryClient.clear()
    }
  }
  const queryCache = new QueryCache({
    onError: handleError,
  })
  const mutationCache = new MutationCache({ onError: handleError })

  queryClient = new QueryClient({
    queryCache,
    mutationCache,
    defaultOptions: {
      queries: {
        retry: shouldRetryQuery,
        retryDelay: 1_000,
        refetchOnReconnect: true,
        refetchOnWindowFocus: true,
        staleTime: 4_000,
      },
    },
  })

  return queryClient
}
