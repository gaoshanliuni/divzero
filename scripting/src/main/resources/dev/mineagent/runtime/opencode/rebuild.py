"""Rebuild the reviewed pure-function adapter from the bundled, hash-pinned MIT sources.

No downloads, package installation, TypeScript compiler or OpenCode agent runtime.
Run with --check in CI, or without arguments to regenerate runtime.js.
"""
from pathlib import Path
import hashlib
import json
import re
import sys

root = Path(__file__).resolve().parent
provenance = json.loads((root / 'provenance.json').read_text(encoding='utf-8'))
sources = {}
for name, info in provenance['files'].items():
    raw = (root / 'upstream' / name).read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != info['sha256']:
        raise SystemExit('Pinned OpenCode source mismatch: ' + name)
    sources[name] = raw.decode('utf-8')

compaction = sources['compaction.ts']
templates = compaction[compaction.index('const SUMMARY_TEMPLATE'):compaction.index('\ntype Entry')].strip()
build = compaction[compaction.index('export const buildPrompt'):compaction.index('\nexport const make')].strip()
build = re.sub(r'export const buildPrompt = \(input: .*?\) =>', 'const buildPrompt = (input) =>', build, count=1)
selection = compaction[compaction.index('  if (conversation.length === 0) return'):compaction.index('\nexport const buildPrompt')].strip()
selection = selection.replace('const next =', 'var next =')  # Rhino's block const is initialized only once across loop iterations.
selection = 'const selectRendered = (conversation, tokens) => {\n' + selection
token = sources['token.ts'].split('export const estimate = ', 1)[1].strip()
token = token.replace('(input: string)', '(input)').replace('CHARS_PER_TOKEN', '4')
processor = sources['processor.ts']
start = processor.index('!recentParts.every(') + 1
end = processor.index('\n            ) {', start)
predicate = processor[start:end].strip()
repeats = 'const repeats = (recentParts, value, input, threshold) => recentParts.length === threshold && ' + predicate + ';'
skill = sources['skill.ts']
start = skill.index('`<skill_content')
end = skill.index('].join("\\n"),', start) + len('].join("\\n")')
renderer = 'const renderSkill = (info, base, files) => { const dir = base; const path = {resolve: (base, resource) => resource}; return [\n              ' + skill[start:end] + '; };'
edit = sources['edit.ts']
matchers = edit[edit.index('export const SimpleReplacer'):edit.index('export const BlockAnchorReplacer')]
matchers += edit[edit.index('export const IndentationFlexibleReplacer'):edit.index('export const EscapeNormalizedReplacer')]
matchers += edit[edit.index('export const TrimmedBoundaryReplacer'):edit.index('export const ContextAwareReplacer')]
replacement = edit[edit.index('export function replace('):]
for unsupported in ['BlockAnchorReplacer', 'WhitespaceNormalizedReplacer', 'EscapeNormalizedReplacer', 'ContextAwareReplacer', 'MultiOccurrenceReplacer']:
    replacement = replacement.replace('    '+unsupported+',\n', '')
edit_runtime = matchers + replacement
edit_runtime = edit_runtime.replace('export const ', 'var ').replace(': Replacer', '').replace('export function ', 'function ')
edit_runtime = edit_runtime.replace(': string', '').replace('(text: string)', '(text)')
# Rhino shares loop-local const bindings; no asynchronous closures are retained by these matchers.
edit_runtime = edit_runtime.replace('const ', 'var ')
edit_runtime = edit_runtime.replace('content.replaceAll(search, newString)', 'content.split(search).join(newString)')
edit_runtime = edit_runtime.replace('Math.min(\n      ...nonEmptyLines.map', 'Math.min.apply(null,\n      nonEmptyLines.map')
edit_runtime = re.sub(r',(?=\s*\))', '', edit_runtime)
runtime = '\n\n'.join([
    '/* Portions copyright (c) 2025 opencode, MIT. See LICENSE and provenance.json. */',
    'var OpenCodeCompat = (function () {',
    'const Token = { estimate: ' + token + ' };', templates, build, selection,
    repeats, renderer, edit_runtime,
    'return {buildPrompt, selectRendered, repeats, renderSkill, estimate: Token.estimate, replace};\n})();',
]) + '\n'
path = root / 'runtime.js'
if '--check' in sys.argv:
    if path.read_text(encoding='utf-8') != runtime:
        raise SystemExit('runtime.js is not reproducible from pinned source')
else:
    path.write_bytes(runtime.encode('utf-8'))
    provenance['runtimeSha256'] = hashlib.sha256(runtime.encode('utf-8')).hexdigest()
    (root / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8', newline='\n')
print('OpenCode pure-function adapter verified at ' + provenance['commit'])
