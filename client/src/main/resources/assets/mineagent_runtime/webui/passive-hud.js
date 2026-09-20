(function () {
  'use strict';
  // Parent iframe inert does not reliably suppress child-document programmatic focus in CEF.
  // This is an input policy, never a substitute for the server's read-only Session.
  if (globalThis.__mineagentPassiveHud) return;
  Object.defineProperty(globalThis, '__mineagentPassiveHud', {value:true});
  const root = document.documentElement;
  const enforce = () => { if (!root.inert) root.inert = true; };
  enforce();
  document.activeElement?.blur();
  new MutationObserver(enforce).observe(root, {attributes:true, attributeFilter:['inert']});
})();
