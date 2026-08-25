// ── profiling.tsx — Opt-in React Profiler instrumentation for perf investigations ──
// Enabled only when the backend was started with PROFILING_MODE=1 (see routes.kt's
// /api/admin/profiling-mode and the session-cookie bypass it shares the flag with).
// Inert in normal use: one small fetch on module load, then a no-op everywhere.

import { Profiler, type ProfilerOnRenderCallback, type ReactNode } from 'react'
import { useProfilingEnabled } from '@/lib/profilingState'

const logProfilerRender: ProfilerOnRenderCallback = (id, phase, actualDuration, baseDuration) => {
  console.log(`[profile] ${id} (${phase}) actual=${actualDuration.toFixed(1)}ms base=${baseDuration.toFixed(1)}ms`)
}

/** Wraps children in a React Profiler when PROFILING_MODE is on; a transparent passthrough otherwise. */
export function ProfileBoundary({ id, children }: { id: string; children: ReactNode }) {
  const profiling = useProfilingEnabled()
  if (!profiling) return <>{children}</>
  return <Profiler id={id} onRender={logProfilerRender}>{children}</Profiler>
}
