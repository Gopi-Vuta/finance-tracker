import { useState, useRef } from 'react';
let csrf;
export async function request(path, options = {}) {
  const method = options.method || 'GET';
  if (method !== 'GET' && !csrf) {
    const response = await fetch('/api/csrf', {credentials: 'same-origin', cache: 'no-store'});
    if (!response.ok) throw new Error('Could not establish a secure session. Reload and try again.');
    csrf = await response.json();
  }
  const response = await fetch(path, {
    ...options, credentials: 'same-origin', cache: 'no-store',
    headers: {'Content-Type': 'application/json', ...(csrf && method !== 'GET' ? {[csrf.headerName]: csrf.token} : {}), ...options.headers},
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    if (response.status === 401 || response.status === 403) csrf = undefined;
    const error = new Error(data.message || (response.status === 403 ? 'Your session changed. Reload and sign in again.' : 'The server could not save this change.'));
    error.status = response.status;
    throw error;
  }
  return response.status === 204 ? null : response.json();
}
export function downloadBackup(state) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(state, null, 2)], {type:'application/json'}));
  const a = document.createElement('a'); a.href = url; a.download = 'PennyFolio-backup.json'; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function useServerState(initial) {
  const [state, render] = useState(initial), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const current = useRef(initial), locked = useRef(false), draft = useRef(null);
  async function update(fn) {
    if (locked.current || draft.current) return false;
    const next = typeof fn === 'function' ? fn(current.current) : fn;
    if (next === current.current) return true;
    draft.current = next;
    return saveDraft();
  }
  async function saveDraft() {
    if (!draft.current || locked.current) return;
    locked.current = true; setBusy(true); setError('');
    const next = draft.current, uid = next.activeUserId;
    try {
      const saved = await request('/api/finance', {method:'PUT', body:{revision:next.revision, user:next.users.find(u=>u.id===uid), months:next.months[uid] || {}}});
      current.current = saved; draft.current = null; render(saved); return true;
    } catch (e) {setError(e.message); return false;}
    finally {locked.current = false; setBusy(false);}
  }
  async function familyAction(path, body={}) {
    if (locked.current || draft.current) throw new Error('Finish or recover the current save first.');
    locked.current = true; setBusy(true);
    try {
      const result = await request(path, {method:'POST', body});
      if (result.version) {current.current=result;render(result);}
      return result;
    } finally {locked.current=false;setBusy(false);}
  }
  return {state,update,busy,error,retry:saveDraft,exportDraft:()=>downloadBackup(draft.current||current.current),familyAction};
}
