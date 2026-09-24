import {t as __uiT,tf as __uiF} from './i18n.mjs';
export class SettingsDraft {
  snapshot=null;changes=new Map();stale=false;baseRevision=-1;
  accept(snapshot,{saved=false,discard=false}={}){
    if(!snapshot||!Number.isSafeInteger(snapshot.revision)||snapshot.revision<0||!Array.isArray(snapshot.fields))throw new Error('SETTINGS_REPLY_INVALID');
    if(this.snapshot&&snapshot.revision<this.snapshot.revision)return false;
    if(saved||discard)this.changes.clear();
    if(!this.changes.size)this.baseRevision=snapshot.revision;
    this.stale=this.changes.size>0&&snapshot.revision!==this.baseRevision;
    this.snapshot=snapshot;return true;
  }
  edit(key,value){const field=this.snapshot?.fields.find(f=>f.key===key);if(!field)throw new Error('SETTINGS_FIELD_DENIED');if(value===field.value)this.changes.delete(key);else this.changes.set(key,value);if(!this.changes.size){this.baseRevision=this.snapshot.revision;this.stale=false;}}
  value(field){return this.changes.get(field.key)??field.value;}
  request(confirmed){if(!this.snapshot||this.stale)throw new Error('STALE_REVISION');if(!this.changes.size)throw new Error('NO_SETTINGS_CHANGES');if([...this.changes.keys()].some(k=>!this.snapshot.fields.some(f=>f.key===k)))throw new Error('FORBIDDEN');return {kind:'save',revision:this.baseRevision,values:Object.fromEntries(this.changes),providerChangeConfirmed:!!confirmed};}
}
export const permissionLabels={CREATE_AGENT:__uiT("创建 AI"),MODIFY_AGENT:__uiT("修改 AI"),CANCEL_AGENT_TASK:__uiT("取消 AI 任务"),RUN_CODE:__uiT("运行代码"),MANAGE_PACKAGES:__uiT("管理内容包"),MANAGE_PROVIDERS:__uiT("管理 Provider（含密钥）"),MANAGE_PERMISSIONS:__uiT("管理权限（可进一步授权）"),CONTROL_PUBLIC_MEDIA:__uiT("管理公共媒体"),MANAGE_SCOREBOARD:__uiT("管理计分"),DISCOVER_OBJECTS:__uiT("发现世界对象"),SUBSCRIBE_EVENTS:__uiT("订阅事件"),SCHEDULE_TASKS:__uiT("安排持久任务"),ACCESS_SHARED_STATE:__uiT("访问共享状态"),OFFER_CONTENT:__uiT("投递内容"),RECEIVE_UI_FEEDBACK:__uiT("接收界面反馈"),RESTORE_BACKUP:__uiT("恢复备份"),VIEW_DIAGNOSTICS:__uiT("查看诊断")};
export const settingsError=code=>({STALE_REVISION:__uiT("配置已更新，未覆盖他人的修改。草稿仍保留，请重新读取或明确放弃草稿。"),FORBIDDEN:__uiT("没有管理此设置的权限。"),PERMISSION_DENIED:__uiT("没有管理此设置的权限。"),PROVIDER_ADDRESS_CONFIRM_REQUIRED:__uiT("更改地址前请确认后续请求将使用当前保存的密钥。"),SETTINGS_PUBLIC_FIELDS_ONLY:__uiT("网页只接受公开设置字段；请使用原生保密输入管理 Key。"),VALIDATION_FAILED:__uiT("请修正标出的字段。")})[code]||code;

export const resourceFieldBounds={
  'runtime.maxAgents':{min:1,max:2147483647},
  'runtime.maxChunkTickets':{min:0,max:100},
  'runtime.agentTicketRadius':{min:0,max:2},
};
export function resourceStatusText(limits){
  const state={APPLIED:__uiT("已应用"),PENDING:__uiT("等待服务端应用"),DEGRADED:__uiT("区块票更新失败，正在重新协调"),STOPPING:__uiT("服务端正在关闭")}[limits.state]||__uiT("状态未知");
  let text=__uiF("资源{0} · AI {1}（不限数量） · 已登记附加区块票 {2} / {3} · 半径 {4}",state,limits.agentCount,limits.activeTickets,limits.maxChunkTickets,limits.agentTicketRadius);
  if(limits.overAgentLimit)text+=__uiT("。现有 AI 超过新建上限，全部保留；减少至上限以下后才能新建");
  if(limits.maxChunkTickets===0)text+=__uiT("。附加区块票已禁用");
  else if(limits.limitedAgents>0)text+=__uiF("。{0} 个 AI 暂未获得完整区块票，活动依赖世界中已加载的区块",limits.limitedAgents);
  return text+'。';
}
export function resourceDraftText(draft){
  const limits=draft.snapshot?.limits;if(!limits)return '';
  const value=(key,fallback)=>{const raw=String(draft.changes.get(key)??fallback);return raw.trim()===''?NaN:Number(raw);};
  const agents=value('runtime.maxAgents',limits.maxAgents),budget=value('runtime.maxChunkTickets',limits.maxChunkTickets),radius=value('runtime.agentTicketRadius',limits.agentTicketRadius);
  if(!Number.isInteger(agents)||agents<1||agents>4||!Number.isInteger(budget)||budget<0||budget>100||!Number.isInteger(radius)||radius<0||radius>2)return __uiT("请按标注范围填写整数；无效值不会保存。");
  const size=(radius*2+1)**2;
  let text=__uiF("草稿：半径 {0} 需要每个 AI 完整 {1} 张票；不同 AI 重叠的区块只计一次。",radius,size);
  if(agents<limits.agentCount)text+=__uiT(" 保存后不会删除或卸载现有 AI，只拒绝超限新建。");
  if(budget===0)text+=__uiT(" 保存后释放全部 AI 附加票，不删除身体、物品或任务。");
  else if(budget<size)text+=__uiT(" 当前预算容不下任何一个完整范围，所有 AI 都不会获得附加票。");
  else if(budget<size*limits.agentCount)text+=__uiT(" 分散的 AI 可能因预算不足暂时没有附加票；不自动缩小其半径。");
  return text;
}
