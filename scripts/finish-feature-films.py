"""Add an original, locally synthesized instrumental bed and a short trailer.
No external songs, samples, voice recordings, network services or credentials are used.
"""
from pathlib import Path
import argparse, json, math, subprocess, wave
import numpy as np


def duration(probe, path):
    return float(subprocess.check_output([probe, '-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', str(path)]))


def soundtrack(path, seconds):
    rate = 32000
    mix = np.zeros((math.ceil((seconds + 1) * rate), 2), dtype=np.float32)
    rng = np.random.default_rng(260925)
    beat = 60 / 88
    chords = [(50,54,57,61), (47,50,54,57), (43,47,50,54), (45,49,52,57)]
    def note(midi, at, length, amplitude, pan, pad=False):
        start = round(at * rate)
        count = min(round(length * rate), len(mix)-start)
        if count <= 0:
            return
        t = np.arange(count, dtype=np.float32) / rate
        hz = 440 * 2 ** ((midi-69) / 12)
        if pad:
            env = np.minimum(t/.7,1) * np.minimum((length-t)/1.2,1)
            signal = (.7*np.sin(2*np.pi*hz*t) + .2*np.sin(2*np.pi*(hz*1.002)*t) + .1*np.sin(2*np.pi*hz*2*t))
        else:
            env = np.minimum(t/.012,1) * np.exp(-t/1.25) * np.minimum((length-t)/.09,1)
            signal = np.sin(2*np.pi*hz*t) + .22*np.sin(2*np.pi*hz*2*t) + .055*np.sin(2*np.pi*hz*3*t)
        signal *= env * amplitude
        mix[start:start+count,0] += signal * math.sqrt((1-pan)/2)
        mix[start:start+count,1] += signal * math.sqrt((1+pan)/2)
    bars = math.ceil(seconds/(4*beat))
    patterns = [(0,1,2,1,3,2,1,2), (0,2,1,2,3,1,2,1), (0,1,3,2,1,2,0,1)]
    for bar in range(bars):
        at = bar * 4 * beat
        chord = chords[(bar//2)%4]
        if bar%2 == 0:
            for i,pitch in enumerate(chord):
                note(pitch+12,at,8*beat+.5,.012,-.55+i*.36,True)
        note(chord[0]-12,at,2.1*beat,.045,0)
        note(chord[0]-12,at+2*beat,1.7*beat,.027,0)
        pattern=patterns[(bar//8)%len(patterns)]
        for step,index in enumerate(pattern):
            if bar%8==7 and step>5:
                continue
            pitch=chord[index]+24
            if bar%16>=8 and step in (3,7):
                pitch-=12
            note(pitch,at+step*.5*beat,2.5,.018*(.78+.22*rng.random()),(-.32 if step%2 else .32))
    # Quiet, diffuse echoes; no beat-synchronized game events are fabricated.
    for delay, gain in [(round(beat*.75*rate),.14),(round(beat*1.5*rate),.07)]:
        mix[delay:] += gain * mix[:-delay,::-1].copy()
    fade=min(round(3*rate),len(mix)//2)
    mix[:fade] *= np.linspace(0,1,fade,dtype=np.float32)[:,None]
    mix[-fade:] *= np.linspace(1,0,fade,dtype=np.float32)[:,None]
    peak=float(np.max(np.abs(mix)))
    if peak:
        mix *= .55/peak
    assert np.isfinite(mix).all()
    path.parent.mkdir(parents=True,exist_ok=True)
    with wave.open(str(path),'wb') as w:
        w.setnchannels(2);w.setsampwidth(2);w.setframerate(rate)
        w.writeframes((np.clip(mix,-1,1)*32767).astype('<i2').tobytes())
    return {'source':'original procedural composition; no external audio','seed':260925,'bpm':88,'sampleRate':rate,'peakBeforeNormalization':peak,'duration':len(mix)/rate}


def card(ffmpeg, work, name, headline, chinese, english, seconds):
    ass=work/(name+'.ass')
    ass.write_text('''[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Main,Microsoft YaHei,112,&H00E3F8D9,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,10,0,1,0,0,5,100,100,0,1
Style: Sub,Microsoft YaHei,45,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,0,0,5,100,100,0,1
Style: English,Microsoft YaHei,30,&H00BCCABB,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,1,0,1,0,0,5,100,100,0,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
'''+f'Dialogue: 0,0:00:00.00,0:00:10.00,Main,,0,0,0,,{{\\pos(960,410)\\fad(350,350)}}{headline}\n'+f'Dialogue: 0,0:00:00.00,0:00:10.00,Sub,,0,0,0,,{{\\pos(960,565)\\fad(350,350)}}{chinese}\n'+f'Dialogue: 0,0:00:00.00,0:00:10.00,English,,0,0,0,,{{\\pos(960,650)\\fad(350,350)}}{english}\n',encoding='utf-8-sig')
    video=work/(name+'.mp4')
    subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-y','-f','lavfi','-i',f'color=c=0x101814:s=1920x1080:r=30:d={seconds}','-vf','ass='+ass.name,'-an','-c:v','h264_nvenc','-preset','p5','-cq','18','-b:v','0','-pix_fmt','yuv420p',video.name],cwd=work,check=True)
    return video


def finish(manifest):
    data=json.loads(Path(manifest).read_text(encoding='utf-8-sig'))
    root=Path(data['output']).resolve();ff=data['ffmpeg'];probe=str(Path(ff).with_name('ffprobe'+Path(ff).suffix))
    work=root.parent/'finishing';work.mkdir(exist_ok=True)
    full=root/'DivZero-full-showcase-1080p.mp4';silent=root/'DivZero-full-showcase-silent-1080p.mp4'
    if not silent.exists():
        full.rename(silent)
    total=duration(probe,silent)
    music=work/'original-background.wav';info=soundtrack(music,total)
    def mux(source,target):
        seconds=duration(probe,source)
        subprocess.run([ff,'-hide_banner','-loglevel','error','-y','-i',str(source),'-i',str(music),'-map','0:v:0','-map','1:a:0','-c:v','copy','-c:a','aac','-b:a','192k','-ar','48000','-af',f'loudnorm=I=-21:TP=-2:LRA=7,afade=t=out:st={max(0,seconds-2.5)}:d=2.5','-t',str(seconds),'-movflags','+faststart',str(target)],check=True)
    mux(silent,full)
    intro=card(ff,work,'intro','DIVZERO','把想法带进 Minecraft','Chat. Create. Play.',3)
    outro=card(ff,work,'outro','DIVZERO','更多玩法，从一句话开始','github.com/gaoshanliuni/divzero',3.5)
    heroes=['basketball','entity-part-replacement','native-entities','bones','geometry','build-path']
    indices=sorted(range(len(data['clips'])),key=lambda i:(heroes.index(data['clips'][i]['slug']) if data['clips'][i]['slug'] in heroes else len(heroes)+i))
    pieces=[intro]
    for serial,index in enumerate(indices):
        clip=data['clips'][index];file=root/'clips'/f'{index+1:02}-{clip["slug"]}.mp4'
        seconds=duration(probe,file);start=min(max(0,seconds*.4),max(0,seconds-3.1))
        if clip['slug']=='basketball':start=14
        if clip['slug']=='geometry':start=36
        if clip['slug']=='entity-part-replacement':start=3
        target=work/f'trailer-{serial:02}.mp4'
        subprocess.run([ff,'-hide_banner','-loglevel','error','-y','-ss',str(start),'-i',str(file),'-t','3','-an','-c:v','h264_nvenc','-preset','p5','-cq','18','-b:v','0','-pix_fmt','yuv420p','-r','30',str(target)],check=True)
        pieces.append(target)
    pieces.append(outro)
    join=work/'trailer-concat.txt';join.write_text('\n'.join("file '"+p.name+"'" for p in pieces)+'\n',encoding='utf-8')
    trailer_silent=root/'DivZero-trailer-silent-1080p.mp4';trailer=root/'DivZero-trailer-1080p.mp4'
    subprocess.run([ff,'-hide_banner','-loglevel','error','-y','-f','concat','-safe','0','-i',str(join),'-c','copy','-movflags','+faststart',str(trailer_silent)],check=True)
    mux(trailer_silent,trailer)
    info.update({'fullDuration':duration(probe,full),'trailerDuration':duration(probe,trailer),'clips':len(data['clips'])})
    (root/'music-and-master-info.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(info,ensure_ascii=False),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('manifest');finish(parser.parse_args().manifest)
