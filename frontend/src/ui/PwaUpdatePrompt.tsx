import { useRegisterSW } from 'virtual:pwa-register/react'
import { PwaUpdateNotice } from './PwaUpdateNotice'

export function PwaUpdatePrompt() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    offlineReady: [offlineReady, setOfflineReady],
    updateServiceWorker,
  } = useRegisterSW()

  return (
    <PwaUpdateNotice
      needRefresh={needRefresh}
      offlineReady={offlineReady}
      onDismiss={() => {
        setNeedRefresh(false)
        setOfflineReady(false)
      }}
      onUpdate={() => void updateServiceWorker(true)}
    />
  )
}
