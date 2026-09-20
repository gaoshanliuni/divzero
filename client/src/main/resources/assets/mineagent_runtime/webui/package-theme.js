(function(css){
  'use strict';
  let style=document.getElementById('mineagent-package-theme');
  if(!style){style=document.createElement('style');style.id='mineagent-package-theme';(document.head||document.documentElement).append(style);}
  if(style.textContent!==css)style.textContent=css;
  document.documentElement.setAttribute('data-mineagent-theme','glass-sage');
})
