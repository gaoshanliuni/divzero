import {messages} from './i18n-messages.mjs';
const language=globalThis.document?.documentElement?.dataset?.uiLanguage==='en_us'?'en_us':'zh_cn';
export const uiLanguage=language;
export function translate(locale,source,context){return locale==='en_us'?((context?messages[source+'::'+context]:undefined)??messages[source]??source):source;}
export function t(source,context){return translate(language,source,context);}
export function format(locale,source,...args){return translate(locale,source).replace(/\{(\d+)\}/g,(all,index)=>Number(index)<args.length?String(args[Number(index)]):all);}
export function tf(source,...args){return format(language,source,...args);}
export function localizeStaticHtml(root=document){for(const n of root.querySelectorAll('[data-ui-text]'))n.textContent=t(n.dataset.uiText);for(const n of root.querySelectorAll('[data-ui-aria]'))n.setAttribute('aria-label',t(n.dataset.uiAria));document.documentElement.lang=language==='en_us'?'en':'zh-CN';}
