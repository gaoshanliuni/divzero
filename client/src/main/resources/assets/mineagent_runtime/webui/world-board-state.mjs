export class WorldBoardDraft {
  constructor(){this.revision=0;this.fields={};this.dirty=false;this.operationId=null;this.action=null;}
  update(view){if(this.dirty)return;this.revision=view.revision;this.fields={...view.target,dimension:view.target?.dimension||"minecraft:overworld",viewDistance:view.viewDistance??128};}
  edit(key,value){if(!['x','y','z','yaw','scale','dimension','viewDistance'].includes(key))throw new Error('WORLD_BOARD_FIELD');this.fields[key]=value;this.dirty=true;this.operationId=null;}
  request(action,newId){
    const coordinates={};
    if(action==='worldMove')for(const key of ['x','y','z','yaw','scale','viewDistance']){const value=Number(this.fields[key]);if(!Number.isFinite(value)||String(this.fields[key]).trim()==='')throw new Error('WORLD_BOARD_COORDINATES');coordinates[key]=value;}
    if(action==='worldMove'){if(!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(this.fields.dimension)||!Number.isInteger(coordinates.viewDistance)||coordinates.viewDistance<1||coordinates.viewDistance>128)throw new Error('WORLD_BOARD_TARGET');coordinates.dimension=this.fields.dimension;}
    if(action!==this.action){this.operationId=null;this.action=action;}
    this.operationId??=newId();return {expectedViewRevision:this.revision,operationId:this.operationId,...coordinates};
  }
}
