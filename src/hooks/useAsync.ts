import { useCallback, useEffect, useState } from 'react';

export function useAsync<T>(loader: () => Promise<T>, dependencies: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [attempt, setAttempt] = useState(0);
  const retry = useCallback(() => setAttempt((value) => value + 1), []);

  useEffect(() => {
    let active = true;
    setLoading(true); setError(null); setData(null);
    loader().then((value) => { if (active) setData(value); }).catch((reason) => { if (active) setError(reason instanceof Error ? reason : new Error(String(reason))); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...dependencies, attempt]);

  return { data, loading, error, retry };
}
