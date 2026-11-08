"""Exact ZIP member comparisons, no application content digests. Compilation stays in Actions."""
import io,json,sys,zipfile
from pathlib import Path
WORKER='META-INF/mineagent/worker/mineagent-worker.jar'

def members(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return {entry.filename:archive.read(entry) for entry in archive.infolist() if not entry.is_dir()}

def jar(files):
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for name,data in sorted(files.items()):archive.writestr(name,data)
    return out.getvalue()

def changes(base,current,prefix,archive):
    changed=[]
    for name,data in current.items():
        if name not in base or base[name]!=data:
            path=prefix+str(len(changed));archive.writestr(path,data);changed.append([name,path,len(data)])
    return {'changed':changed,'removed':sorted(set(base)-set(current)),'members':sorted(current)}

def apply(base,manifest,archive):
    out=dict(base)
    for name in manifest['removed']:out.pop(name)
    for name,path,length in manifest['changed']:
        data=archive.read(path)
        if len(data)!=length:raise ValueError('OVERLAY_MEMBER_LENGTH')
        out[name]=data
    if sorted(out)!=manifest['members']:raise ValueError('OVERLAY_BASE_MEMBER_SET')
    return out

def assemble(base,overlay):
    files=members(base)
    with zipfile.ZipFile(io.BytesIO(overlay)) as archive:
        manifest=json.loads(archive.read('manifest.json'))
        if manifest['baseRun']!='42450475612':raise ValueError('OVERLAY_BASE_RUN')
        worker=jar(apply(members(files[WORKER]),manifest['worker'],archive))
        files.pop(WORKER);files=apply(files,manifest['root'],archive);files[WORKER]=worker
    return jar(files)

if __name__=='__main__':
    base,current,out=map(Path,sys.argv[1:4]);source=sys.argv[4]
    old,new=members(base.read_bytes()),members(current.read_bytes())
    old_worker,new_worker=members(old.pop(WORKER)),members(new.pop(WORKER))
    out.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        manifest={'baseRun':'42450475612','source':source,'worker':changes(old_worker,new_worker,'worker/',archive),'root':changes(old,new,'root/',archive)}
        archive.writestr('manifest.json',json.dumps(manifest))
    rebuilt=members(assemble(base.read_bytes(),out.read_bytes()))
    assert members(rebuilt.pop(WORKER))==new_worker
    assert rebuilt==new
    print(json.dumps({'source':source,'overlayBytes':out.stat().st_size,'rootEntries':len(new),'workerEntries':len(new_worker),'exactMemberComparison':True}))
