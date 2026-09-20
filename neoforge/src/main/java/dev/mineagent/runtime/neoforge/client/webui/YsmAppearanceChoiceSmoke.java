package dev.mineagent.runtime.neoforge.client.webui;

/** Explicit local production fixture: operates real trusted controls, never calls a Provider. */
final class YsmAppearanceChoiceSmoke {
    private YsmAppearanceChoiceSmoke() {}

    static String script(String agentJson) {
        return """
            const s=window.__choiceSmoke??={step:0,phase:'read',results:[],known:[],error:''};
            if(s.done||s.error)return;
            try{
              const modes=['selected','custom','chat','default','unparsed','selected'];
              const mode=modes[s.step];
              const input=(selector,value)=>{const field=document.querySelector(selector);if(!field)throw new Error('MISSING_FIELD '+selector);field.value=value;field.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));};
              if(s.phase==='read'){
                document.querySelector('#open-appearance').click();const agent=document.querySelector('#appearance-agent');
                if(![...agent.options].some(o=>o.value===AGENT))return;
                agent.value=AGENT;agent.dispatchEvent(new Event('change',{bubbles:true}));s.phase='create';return;
              }
              if(s.phase==='create'){
                const choice=document.querySelector('#appearance-decide');if(!choice||choice.disabled)return;
                for(const [key,value] of Object.entries({model:'default',texture:'',animation:''}))input('#appearance-'+key,value);
                s.known=[...document.querySelectorAll('[data-decision-id]')].map(n=>n.dataset.decisionId);
                choice.click();s.phase='answer';return;
              }
              const card=s.id?document.querySelector('[data-decision-id="'+s.id+'"]'):[...document.querySelectorAll('[data-decision-id]')].find(n=>!s.known.includes(n.dataset.decisionId));
              if(!card)return;s.id=card.dataset.decisionId;
              if(s.phase==='answer'){
                if(mode==='chat'){
                  document.querySelector('#open-chat').click();const agent=document.querySelector('#chat-agent');agent.value=AGENT;agent.dispatchEvent(new Event('change',{bubbles:true}));
                  const target=document.querySelector('#chat-decision');if(![...target.options].some(o=>o.value===s.id))return;
                  target.value=s.id;input('#chat-draft','第1个，但 texture=blue animation=idle');
                  const send=[...document.querySelector('#chat-draft').parentElement.querySelectorAll('button')].find(b=>b.textContent==='发送私密消息');send.click();
                }else{
                  if(mode!=='custom')card.querySelector('[data-option-id="typed"]').click();
                  const extra=mode==='default'?'':mode==='unparsed'?'但请更漂亮些':(mode==='custom'?'model=default ':'')+'texture=blue animation=idle';
                  const text=card.querySelector('textarea');text.value=extra;text.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:extra}));
                  card.querySelector('[data-ai-id="decision-submit"]').click();
                }
                s.phase='verify';return;
              }
              const effect=card.querySelector('[data-domain-effect]');if(!effect||effect.dataset.domainEffect==='APPLYING')return;
              const expected=mode==='unparsed'?'FAILED':'APPLIED';
              if(effect.dataset.domainEffect!==expected||mode==='unparsed'&&!effect.textContent.includes('APPEARANCE_DETAILS_REQUIRED'))throw new Error(mode+': '+effect.textContent);
              s.results.push({mode,decisionId:s.id,effect:effect.textContent,answer:card.querySelector('textarea').value,theme:document.documentElement.dataset.mineagentTheme??'',windowBackground:getComputedStyle(card.closest('.window')??card).backgroundColor});
              s.step++;delete s.id;s.phase='read';
              if(s.step===modes.length){s.done=true;document.querySelector('#appearance-fields').dataset.appearanceRevision=effect.textContent.match(/ · r([0-9]+)/)[1];}
            }catch(e){s.error=String(e);}
            """.replace("AGENT", agentJson);
    }
}
