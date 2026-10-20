import { useEffect, useState } from 'react';
import { Desk } from './Desk';
import { Portal } from './Portal';

function useHash() {
  const [hash, setHash] = useState(() => window.location.hash);
  useEffect(() => {
    const on = () => setHash(window.location.hash);
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return hash;
}

export default function App() {
  const hash = useHash();
  const m = /^#\/portal\/([^/?#]+)/.exec(hash);
  if (m) return <Portal key={m[1]} id={decodeURIComponent(m[1])} />;
  return <Desk />;
}
