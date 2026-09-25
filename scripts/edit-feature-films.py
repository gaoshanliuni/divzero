"""Cut reviewed native recordings into bilingual clips and an ordered full showcase.
The edit JSON contains explicit, reviewed in/out points; no generated game footage.
"""
from pathlib import Path
import argparse, html, json, subprocess


def timestamp(seconds):
    cs = round(seconds * 100)
    return f'{cs//360000}:{cs//6000%60:02}:{cs//100%60:02}.{cs%100:02}'


def text(value):
    return str(value).replace('\\', '/').replace('{', '(').replace('}', ')').replace('\n', r'\N')


def captions(clip, seconds, index):
    header = '''[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 0
ScaledBorderAndShadow: yes
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Brand,Microsoft YaHei,25,&H00DDE7D2,&H00FFFFFF,&H90000000,&H90000000,1,0,0,0,100,100,3,0,1,2,1,7,66,66,44,1
Style: Title,Microsoft YaHei,42,&H00FFFFFF,&H00FFFFFF,&H80000000,&H80000000,1,0,0,0,100,100,1,0,1,2,1,7,66,66,88,1
Style: English,Microsoft YaHei,25,&H00F1F1F1,&H00FFFFFF,&H80000000,&H80000000,0,0,0,0,100,100,0,0,1,2,1,7,68,66,149,1
Style: Chinese,Microsoft YaHei,36,&H00FFFFFF,&H00FFFFFF,&H90000000,&H90000000,1,0,0,0,100,100,0,0,1,2,1,2,90,90,112,1
Style: Subtitle,Microsoft YaHei,27,&H00E4EDE5,&H00FFFFFF,&H90000000,&H90000000,0,0,0,0,100,100,0,0,1,2,1,2,90,90,66,1
Style: Note,Microsoft YaHei,20,&H00DADADA,&H00FFFFFF,&H90000000,&H90000000,0,0,0,0,100,100,0,0,1,1,1,3,64,64,26,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
'''
    if clip.get('caption_layout') == 'chat':
        header = header.replace('2,90,90,112,1', '8,1040,50,250,1').replace('2,90,90,66,1', '8,1040,50,302,1')
    rows = []
    def add(start, end, style, value):
        rows.append(f'Dialogue: 0,{timestamp(start)},{timestamp(end)},{style},,0,0,0,,{{\\fad(220,220)}}{text(value)}')
    add(0, seconds, 'Brand', f'DIVZERO  /  {index:02}')
    add(0, seconds, 'Title', clip['title_zh'])
    add(0, seconds, 'English', clip['title_en'])
    for subtitle in clip.get('captions', []):
        add(subtitle['start'], min(seconds, subtitle['end']), 'Chinese', subtitle['zh'])
        add(subtitle['start'], min(seconds, subtitle['end']), 'Subtitle', subtitle['en'])
    note = clip.get('note', '')
    if clip.get('speed', 1) != 1:
        note += f'  ·  {clip["speed"]}× 慢放 / Slow motion'
    if note:
        add(0, seconds, 'Note', note)
    return header + '\n'.join(rows) + '\n'


def build(manifest):
    data = json.loads(Path(manifest).read_text(encoding='utf-8-sig'))
    output = Path(data['output']).resolve()
    clips_dir = output / 'clips'
    clips_dir.mkdir(parents=True, exist_ok=True)
    ffmpeg = data['ffmpeg']
    ffprobe = str(Path(ffmpeg).with_name('ffprobe' + Path(ffmpeg).suffix))
    chapters, chapter_times, gallery, playlist, receipts = [], [], [], [], []
    elapsed = 0
    for index, clip in enumerate(data['clips'], 1):
        assert clip.get('reviewed') is True, 'CLIP_REQUIRES_VISUAL_REVIEW'
        speed = clip.get('speed', 1)
        assert .25 <= speed <= 2
        duration = (clip['end'] - clip['start']) / speed
        assert duration > 0 and Path(clip['source']).is_file()
        name = f'{index:02}-{clip["slug"]}'
        subtitle = clips_dir / (name + '.ass')
        subtitle.write_text(captions(clip, duration, index), encoding='utf-8-sig')
        video = clips_dir / (name + '.mp4')
        filters = f'setpts=(PTS-STARTPTS)/{speed},drawbox=x=0:y=0:w=iw:h=205:color=black@0.22:t=fill,'
        filters += 'drawbox=x=1020:y=220:w=860:h=185:color=black@0.35:t=fill,' if clip.get('caption_layout') == 'chat' else 'drawbox=x=0:y=ih-180:w=iw:h=180:color=black@0.24:t=fill,'
        filters += 'ass=' + subtitle.name + ',fade=t=in:st=0:d=0.25,fade=t=out:st=' + str(max(0, duration-.3)) + ':d=0.3'
        command = [ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-ss', str(clip['start']), '-i', str(Path(clip['source']).resolve()),
                   '-t', str(duration), '-vf', filters, '-an', '-c:v', 'h264_nvenc', '-preset', 'p5', '-cq', '18', '-b:v', '0',
                   '-pix_fmt', 'yuv420p', '-r', '30', '-movflags', '+faststart', video.name]
        subprocess.run(command, cwd=clips_dir, check=True)
        facts = json.loads(subprocess.check_output([ffprobe, '-v', 'error', '-select_streams', 'v:0',
            '-show_entries', 'stream=codec_name,width,height,r_frame_rate,pix_fmt:format=duration', '-of', 'json', str(video)]))
        stream = facts['streams'][0]
        actual_duration = float(facts['format']['duration'])
        assert (stream['codec_name'], stream['width'], stream['height'], stream['r_frame_rate'], stream['pix_fmt']) == ('h264', 1920, 1080, '30/1', 'yuv420p')
        assert abs(actual_duration-duration) < .11, ('CUT_DURATION_MISMATCH', name, actual_duration, duration)
        thumb = clips_dir / (name + '.jpg')
        subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-y','-ss',str(min(3,duration/2)),'-i',str(video),'-frames:v','1','-vf','scale=640:360',str(thumb)], check=True)
        whole = round(elapsed)
        if not chapter_times or elapsed-chapter_times[-1]>=10:
            chapters.append(f'{whole//60:02}:{whole%60:02} {clip["title_zh"]} / {clip["title_en"]}')
            chapter_times.append(elapsed)
        elapsed += actual_duration
        playlist.append("file 'clips/" + video.name + "'")
        gallery.append(f'<article><video controls preload="none" poster="clips/{thumb.name}" src="clips/{video.name}"></video><h2>{index:02} · {html.escape(clip["title_zh"])}</h2><p>{html.escape(clip["title_en"])}</p></article>')
        source_profile=Path(clip['source']).parent.parent.parent.name
        receipts.append({'file':video.name,'sourceProfile':source_profile,'start':clip['start'],'end':clip['end'],'scenario':clip.get('scenario'),'note':clip.get('note',''),'verificationNote':clip.get('verification_note',''),'video':facts})
        print('EDITED ' + video.name, flush=True)
    concat = output / 'concat.txt'
    concat.write_text('\n'.join(playlist)+'\n', encoding='utf-8')
    subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-y','-f','concat','-safe','0','-i',str(concat),'-c','copy','-movflags','+faststart',str(output/'DivZero-full-showcase-1080p.mp4')],check=True)
    if len(chapters)>1 and elapsed-chapter_times[-1]<10:
        chapters.pop()
    (output/'YouTube-chapters.txt').write_text('\n'.join(chapters)+'\n',encoding='utf-8-sig')
    (output/'footage-provenance.json').write_text(json.dumps(receipts,ensure_ascii=False,indent=2),encoding='utf-8')
    (output/'index.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>DivZero · Feature films</title><style>body{background:#101814;color:#f1f5ef;font:16px system-ui;margin:40px auto;max-width:1440px;padding:0 28px}h1{font-size:44px}p{color:#b8c9bb}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(400px,1fr));gap:28px}video{width:100%;border-radius:12px}h2{font-size:20px}article{background:#18231d;border-radius:14px;padding:14px}a{color:#9dddaf}</style><h1>DIVZERO</h1><p>功能演示 · Feature films · 1080p / 30 fps · 中英双语</p><p><a href="DivZero-full-showcase-1080p.mp4">完整总览 / Full showcase</a> · <a href="YouTube-chapters.txt">YouTube 章节</a></p><main class="grid">'+''.join(gallery)+'</main></html>',encoding='utf-8')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('manifest')
    build(p.parse_args().manifest)
