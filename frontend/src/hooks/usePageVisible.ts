import { useEffect, useState } from 'react'

export function usePageVisible() {
  const [visible, setVisible] = useState(
    () => document.visibilityState === 'visible',
  )

  useEffect(() => {
    const handleVisibility = () => {
      setVisible(document.visibilityState === 'visible')
    }
    document.addEventListener('visibilitychange', handleVisibility)
    return () => {
      document.removeEventListener('visibilitychange', handleVisibility)
    }
  }, [])

  return visible
}

