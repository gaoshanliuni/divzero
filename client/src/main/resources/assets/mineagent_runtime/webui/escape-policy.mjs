// Native Escape may not become a usable DOM key in the pinned host. Never bypass IME or a child frame's handling.
export function releaseTrustedEscape(composing,activeTag){return composing!==true&&activeTag!=='IFRAME';}
