import { QueryCache, QueryClient } from '@tanstack/react-query'
import { type ApiError, isAuthorizationError, shouldRetryQuery } from './api/client'

export function createHomeOpsQueryClient(onAuthorizationError: (error: ApiError) => void) {
  let queryClient: QueryClient
  const queryCache = new QueryCache({
    onError: (error) => {
      if (isAuthorizationError(error)) {
        onAuthorizationError(error)
        queryClient.clear()
      }
    },
  })

  queryClient = new QueryClient({
    queryCache,
    defaultOptions: {
      queries: {
        retry: shouldRetryQuery,
        refetchOnReconnect: true,
        refetchOnWindowFocus: true,
        staleTime: 4_000,
      },
    },
  })

  return queryClient
}
