import fs from 'node:fs';
import path from 'node:path';

// A skill is a .md file directly inside an extension's skills/ folder; its name is the file name.
// A folder with the same name next to it holds that skill's detail files, which the skill can point to.
const NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const MAX_DETAIL_FILES = 200, MAX_READ = 512 * 1024;
export const SKILL_TOOL_NAMES = ['get_skills', 'get_skill', 'get_skill_file'];

const skillsDir = dir => path.join(dir, 'skills');
const inside = (root, file) => { const real = fs.realpathSync(file); return real === root || real.startsWith(root + path.sep) ? real : null; };
const readText = file => { const size = fs.statSync(file).size; const text = fs.readFileSync(file, 'utf8').slice(0, MAX_READ); return size > MAX_READ ? text + `\n\n[Truncated: file is ${size} bytes, first ${MAX_READ} shown]` : text; };

/** Title and description from optional `---` frontmatter, else the first heading and paragraph. */
export function describe(text, fallback) {
  let body = text, meta = {};
  const front = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
  if (front) { body = text.slice(front[0].length); for (const line of front[1].split(/\r?\n/)) { const m = /^([A-Za-z_-]+):\s*(.*)$/.exec(line); if (m) meta[m[1].toLowerCase()] = m[2].trim().replace(/^(['"])(.*)\1$/, '$2'); } }
  const lines = body.split(/\r?\n/); const heading = lines.find(l => /^#\s+\S/.test(l));
  let description = meta.description || '';
  if (!description) {
    const start = heading ? lines.indexOf(heading) + 1 : 0; const paragraph = [];
    for (const line of lines.slice(start)) { const t = line.trim(); if (!t) { if (paragraph.length) break; continue; } if (/^(#|```|[-*]\s|\d+\.\s|\||>)/.test(t)) { if (paragraph.length) break; continue; } paragraph.push(t); }
    description = paragraph.join(' ').replace(/[*_`]/g, '').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');
  }
  if (description.length > 240) description = description.slice(0, 237).trimEnd() + '…';
  return { title: meta.name || meta.title || (heading ? heading.replace(/^#\s+/, '').trim() : fallback), description };
}

function detailFiles(root, name) {
  const base = path.join(root, name); const files = [];
  if (!fs.existsSync(base) || !fs.statSync(base).isDirectory()) return files;
  const walk = dir => { for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    if (files.length >= MAX_DETAIL_FILES || entry.name.startsWith('.')) continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(file); else if (entry.isFile()) files.push(path.relative(base, file).split(path.sep).join('/'));
  } };
  walk(base); return files;
}

export function listSkills(dir) {
  const root = skillsDir(dir);
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) return [];
  const real = fs.realpathSync(root);
  return fs.readdirSync(real, { withFileTypes: true })
    .filter(e => e.isFile() && e.name.toLowerCase().endsWith('.md') && NAME.test(e.name.slice(0, -3)))
    .map(e => { const name = e.name.slice(0, -3), file = path.join(real, e.name); return { name, ...describe(readText(file), name), path: file, detail_files: detailFiles(real, name) }; })
    .sort((a, b) => a.name.localeCompare(b.name));
}

function findSkill(dir, name) {
  const skill = listSkills(dir).find(s => s.name === name);
  if (!skill) { const names = listSkills(dir).map(s => s.name); throw new Error(`Unknown skill: ${name}. Available: ${names.join(', ') || '(none)'}`); }
  return skill;
}

export function readSkill(dir, name) {
  const skill = findSkill(dir, String(name ?? ''));
  const detailDir = path.join(path.dirname(skill.path), skill.name);
  return { name: skill.name, title: skill.title, path: skill.path, content: readText(skill.path),
    detail_dir: skill.detail_files.length ? detailDir : null,
    detail_files: skill.detail_files.map(file => ({ file, path: path.join(detailDir, file) })) };
}

export function readSkillFile(dir, name, file) {
  const skill = findSkill(dir, String(name ?? ''));
  const relative = String(file ?? '').replace(/\\/g, '/');
  if (!relative || relative.startsWith('/') || relative.split('/').some(p => !p || p === '.' || p === '..')) throw new Error('Use a file path relative to the skill folder, as listed by get_skill');
  const base = path.join(path.dirname(skill.path), skill.name), target = path.join(base, relative);
  if (!fs.existsSync(target) || !fs.existsSync(base) || !inside(fs.realpathSync(base), target) || !fs.statSync(target).isFile()) throw new Error(`No file ${relative} in skill ${skill.name}`);
  return { name: skill.name, file: relative, path: target, content: readText(target) };
}

/** MCP tool specs for an extension's skills; the skill names go in the description so the model sees them up front. */
export function skillTools(skills) {
  const names = skills.map(s => s.name); const shown = names.slice(0, 40).join(', ') + (names.length > 40 ? `, +${names.length - 40} more` : '');
  return [
    { name: 'get_skills', description: `List this plugin's skills (step-by-step guides for its tools) with descriptions and file paths. Read the matching skill before using the other tools. Skills: ${shown}.`, inputSchema: { type: 'object', properties: {} } },
    { name: 'get_skill', description: "Read one skill's full guide. Returns its content, its file path and the detail files it may refer to.", inputSchema: { type: 'object', properties: { name: { type: 'string', description: 'Skill name from get_skills' } }, required: ['name'] } },
    { name: 'get_skill_file', description: "Read one of a skill's detail files, as listed by get_skill. Open these only when the skill points to them.", inputSchema: { type: 'object', properties: { name: { type: 'string', description: 'Skill name' }, file: { type: 'string', description: 'Path relative to the skill folder, e.g. "thumbnails.md"' } }, required: ['name', 'file'] } }
  ];
}

export function callSkillTool(dir, tool, args) {
  if (tool === 'get_skills') return { skills: listSkills(dir).map(({ name, title, description, path: file, detail_files }) => ({ name, title, description, path: file, detail_files: detail_files.length })) };
  if (tool === 'get_skill') return readSkill(dir, args.name);
  if (tool === 'get_skill_file') return readSkillFile(dir, args.name, args.file);
  throw new Error(`Unknown skill tool: ${tool}`);
}

/** Server instructions for MCP `initialize`; clients that support them show this to the model. */
export function skillInstructions(name, skills) {
  if (!skills.length) return undefined;
  return [`This server is the custom AntiGem plugin "${name}". It includes skills: guides for doing tasks with its tools.`,
    'Before using its other tools for a task, find the matching skill below and read it with get_skill. Open detail files with get_skill_file only when the skill refers to them.',
    'Skills:', ...skills.slice(0, 60).map(s => `- ${s.name}${s.description ? ': ' + s.description : ''}`),
    ...(skills.length > 60 ? [`- …and ${skills.length - 60} more (call get_skills)`] : [])].join('\n');
}
