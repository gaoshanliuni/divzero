"""Translate the existing shared navigation algorithms to Java 8, without a new search policy.

Only records, local variable inference and post-Java-8 collection/math syntax are
lowered. The game-side collision/action adapter is maintained separately.
"""
import argparse
import hashlib
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'legacy189/game/src/main/java/dev/mineagent/runtime/legacy189/navigation'


def split_fields(value):
    parts, start, depth = [], 0, 0
    for at, char in enumerate(value):
        depth += (char == '<') - (char == '>')
        if char == ',' and depth == 0:
            parts.append(value[start:at]); start = at + 1
    parts.append(value[start:])
    return [part.strip().rsplit(' ', 1) for part in parts]


def lower_record(source, name, body=''):
    match = re.search(r'(?P<access>public|private) record ' + name + r'\((?P<fields>[^\n]*?)\)\s*\{', source)
    if match is None:
        raise ValueError('Record changed: ' + name)
    end, depth = match.end(), 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    fields = split_fields(match['fields'])
    access = match['access']
    assignments = ';'.join('this.' + n + '=' + n for t, n in fields) + ';'
    declarations = ''.join('public final ' + t + ' ' + n + ';' for t, n in fields)
    methods = ''.join('public ' + t + ' ' + n + '(){return ' + n + ';}' for t, n in fields)
    checks = '&&'.join(('Double.compare(' + n + ',other.' + n + ')==0') if t == 'double' else
                      (n + '==other.' + n) if t in ('int', 'boolean') else 'Objects.equals(' + n + ',other.' + n + ')' for t, n in fields)
    extra = ''
    if name == 'Node': extra = 'public double y(){return y16/16.0;}'
    if name == 'Cell': extra = 'public Cell add(int x,int y,int z){return new Cell(this.x+x,this.y+y,this.z+z);}public int distance(Cell b){return Math.abs(x-b.x)+Math.abs(y-b.y)+Math.abs(z-b.z);}'
    value = access + ' static final class ' + name + '{' + declarations
    value += 'public ' + name + '(' + match['fields'] + '){' + body + assignments + '}' + methods + extra
    value += '@Override public boolean equals(Object value){if(this==value)return true;if(!(value instanceof ' + name + '))return false;' + name + ' other=(' + name + ')value;return ' + checks + ';}'
    value += '@Override public int hashCode(){return Objects.hash(' + ','.join(n for t, n in fields) + ');}}'
    return source[:match.start()] + value + source[end:]


def generate(name):
    original = (ROOT / 'core/src/main/java/dev/mineagent/runtime/core/task' / (name + '.java')).read_bytes().replace(b'\r\n', b'\n')
    source = original.decode('utf-8').replace('package dev.mineagent.runtime.core.task;', 'package dev.mineagent.runtime.legacy189.navigation;')
    if name == 'SurfacePathfinder':
        for record, body in [('Node', ''), ('PathStep', 'Objects.requireNonNull(from);Objects.requireNonNull(to);Objects.requireNonNull(action);Objects.requireNonNull(posture);if(!Double.isFinite(cost)||cost<=0)throw new IllegalArgumentException("PATH_COST");'), ('Result', 'steps=copyList(steps);'), ('Entry', '')]:
            source = lower_record(source, record, body)
        types = {'entry':'Entry','n':'Node','route':'ArrayList<PathStep>','edge':'PathStep','step':'PathStep','next':'Node','search':'Search','edges':'ArrayList<PathStep>','result':'Result','nodes':'ArrayList<Node>'}
    elif name == 'TerrainPathSearch':
        for record, body in [('Cell',''), ('Key',''), ('Edit',''), ('Step','edits=copyList(edits);'), ('Block',''), ('Result','steps=copyList(steps);'), ('State',''), ('Entry','')]:
            source = lower_record(source, record, body)
        types = {'key':'Key','previous':'C','start':'State','current':'Entry','destination':'Cell','changes':'LinkedHashMap<Cell,Kind>','edits':'ArrayList<Edit>','clearance':'LinkedHashSet<Cell>','at':'Cell','block':'Block','floor':'Cell','support':'Block','step':'Step','next':'State','path':'ArrayList<Step>','value':'Block'}
        source = source.replace('Math.clamp(radius,1,8)', 'Math.max(1,Math.min(8,radius))').replace('Math.clamp(learned,0,10)', 'Math.max(0,Math.min(10,learned))')
        source = source.replace('Map.copyOf(changes)', 'Collections.unmodifiableMap(new LinkedHashMap<Cell,Kind>(changes))')
        source = source.replace('return switch(changes.get(cell)){case BREAK->new Block(value.loaded,true,false,false,0,value.state);case PLACE->new Block(value.loaded,false,true,false,0,value.state);case null->value;};',
                                'Kind change=changes.get(cell);if(change==Kind.BREAK)return new Block(value.loaded,true,false,false,0,value.state);if(change==Kind.PLACE)return new Block(value.loaded,false,true,false,0,value.state);return value;')
    else:
        types = {}
    for variable, kind in types.items():
        source = re.sub(r'\bvar\s+' + variable + r'\b', kind + ' ' + variable, source)
    source = source.replace('List.copyOf(', 'copyList(').replace('List.of()', 'Collections.emptyList()').replace('Map.of()', 'Collections.emptyMap()')
    if name != 'NavigationRetry':
        at = source.rfind('}')
        source = source[:at] + 'private static <T> List<T> copyList(Collection<T> values){return Collections.unmodifiableList(new ArrayList<T>(values));}\n' + source[at:]
    if re.search(r'\bvar\b|\brecord\b|List\.of|Map\.of|Math\.clamp|return switch', source):
        raise ValueError('Untranslated Java syntax: ' + name)
    return ('// Generated by legacy189/tools/port_navigation.py from the existing 26.1.2 shared algorithm.\n'
            '// Upstream SHA-256: ' + hashlib.sha256(original).hexdigest() + '\n' + source).encode('utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for name in ('SurfacePathfinder', 'TerrainPathSearch', 'NavigationRetry'):
        output = OUTPUT / (name + '.java')
        data = generate(name)
        if args.check:
            if output.read_bytes().replace(b'\r\n', b'\n') != data: raise ValueError('Generated navigation differs: ' + name)
        else:
            OUTPUT.mkdir(parents=True, exist_ok=True); output.write_bytes(data)
        print(name + ': ' + ('verified' if args.check else 'generated'))
