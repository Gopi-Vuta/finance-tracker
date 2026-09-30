import React from 'react';

/** Short, separately labelled explanations with consistent spacing. */
export function Guidance({items,label='Helpful context',compact=false}) {
  return <aside className={`guidance${compact?' guidance-compact':''}`} aria-label={label}>
    {items.map(({title,text,icon='i'})=><div className="guidance-item" key={title}>
      <span className="guidance-symbol" aria-hidden="true">{icon}</span>
      <div><strong>{title}</strong><p>{text}</p></div>
    </div>)}
  </aside>;
}
