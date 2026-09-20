export class DecisionDraft {
  constructor(request) { this.selected = new Set(); this.customText = ''; this.submissionId = null; this.edited = false; this.update(request); }
  update(request) {
    if (this.request?.decisionId === request.decisionId && request.revision < this.request.revision) return false;
    if (this.request && (this.request.decisionId !== request.decisionId || this.request.revision !== request.revision)) {
      const sameQuestion=['decisionId','taskRevision','recipientPlayerId','kind','title','question','options','selectionMode','minSelections','maxSelections','allowCustomInput'].every(k=>JSON.stringify(this.request[k])===JSON.stringify(request[k]));
      const suspended=this.request.status!==request.status&&['OPEN','DEFERRED'].includes(this.request.status)&&['OPEN','DEFERRED'].includes(request.status);
      const invalidated=['CANCELLED','SUPERSEDED','EXPIRED'].includes(request.status);
      this.submissionId=null;
      if(!sameQuestion||!suspended&&!invalidated){this.selected.clear();this.customText='';this.edited=false;}
    }
    this.request = request;
    return true;
  }
  writable() { if (this.request.status !== 'OPEN') throw new Error('DECISION_NOT_OPEN'); }
  exportDraft() { return { decisionId: this.request.decisionId, revision: this.request.revision,
    selected: [...this.selected], customText: this.customText, submissionId: this.submissionId }; }
  restoreDraft(value) {
    if (this.edited || !value || value.decisionId !== this.request.decisionId || value.revision !== this.request.revision) return;
    if (!Array.isArray(value.selected) || typeof value.customText !== 'string' || value.customText.length > 8192) return;
    const ids = new Set(this.request.options.map(o => o.optionId));
    if (!value.selected.every(id => ids.has(id))) return;
    this.selected = new Set(value.selected); this.customText = this.request.allowCustomInput ? value.customText : '';
    this.submissionId = typeof value.submissionId === 'string' && /^[0-9a-f-]{36}$/.test(value.submissionId) ? value.submissionId : null;
  }
  restoreAccepted(answer) {
    if (this.request.status !== 'RESOLVED' || answer.decisionId !== this.request.decisionId
        || answer.expectedRevision + 1 !== this.request.revision) throw new Error('STALE_ACCEPTED_ANSWER');
    const ids = new Set(this.request.options.map(o => o.optionId));
    if (!answer.selectedOptionIds.every(id => ids.has(id))) throw new Error('UNKNOWN_OPTION');
    this.selected = new Set(answer.selectedOptionIds); this.customText = answer.customText; this.submissionId = answer.submissionId;
  }
  toggle(id) {
    this.writable();
    if (!this.request.options.some(o => o.optionId === id)) throw new Error('UNKNOWN_OPTION');
    if (this.selected.has(id)) this.selected.delete(id);
    else { if (this.request.selectionMode === 'SINGLE') this.selected.clear(); this.selected.add(id); }
    this.submissionId = null;
    this.edited = true;
  }
  edit(text) {
    this.writable();
    if (!this.request.allowCustomInput || text.length > 8192) throw new Error('CUSTOM_INPUT_NOT_ALLOWED');
    if (text !== this.customText) this.submissionId = null;
    this.customText = text;
    this.edited = true;
  }
  submission(idFactory) {
    this.writable(); const n = this.selected.size;
    if (this.request.kind === 'AUTHORIZATION' && n < Math.max(1, this.request.minSelections))
      throw new Error('AUTHORIZATION_CHOICE_REQUIRED');
    if (n > this.request.maxSelections || (n < this.request.minSelections && !this.customText.trim()) || (!n && !this.customText.trim()))
      throw new Error('INVALID_SELECTION_COUNT');
    if (!this.submissionId) this.submissionId = idFactory();
    return { decisionId: this.request.decisionId, expectedRevision: this.request.revision,
      submissionId: this.submissionId, selectedOptionIds: [...this.selected].sort(), customText: this.customText };
  }
}
