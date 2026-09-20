(function () {
  'use strict';
  let pending = 0;
  function request(action, args, operationId) {
    return new Promise((resolve, reject) => {
      if (pending >= 32) return reject(new Error('UI_PENDING_BUDGET'));
      if (typeof window.mineagentContentQuery !== 'function') return reject(new Error('CONTENT_BRIDGE_UNAVAILABLE'));
      const message = JSON.stringify({action, arguments: args, operationId: operationId || crypto.randomUUID()});
      if (message.length > 65536) return reject(new Error('UI_MESSAGE_SIZE'));
      pending++;
      let done = false;
      const finish = (value, error) => { if (done) return; done = true; pending--; error ? reject(error) : resolve(value); };
      try {
        window.mineagentContentQuery({request:message,persistent:false,
          onSuccess(value) {
            try {
              const receipt = JSON.parse(value);
              if (!['OBSERVED','APPLIED'].includes(receipt.code)) throw new Error(receipt.values?.errorCode || receipt.code || 'INVALID_RECEIPT');
              finish(JSON.parse(receipt.values.state));
            } catch (error) { finish(null,error); }
          },
          onFailure(_, code) { finish(null,new Error(code || 'UI_REQUEST_FAILED')); }
        });
      } catch (error) { finish(null,error); }
    });
  }
  const api = Object.freeze({version:1,
    read: () => request('scoreview.read', {}),
    patch: (expectedViewRevision, layout, operationId) => request('scoreview.patch',
      {expectedViewRevision:String(expectedViewRevision),patch:JSON.stringify(layout)},operationId)
  });
  Object.defineProperty(window,'mineagentUi',{value:api,configurable:true,writable:false});
  window.dispatchEvent(new CustomEvent('mineagent:ready'));
})();
