import { readFile } from 'node:fs/promises'
import ts from 'typescript'

let graph = 0
/** Isolated real TS modules for Node tests, without a browser bundler. */
export async function loadTypeScript(entry) {
  const modules = new Map(), id = graph++
  async function compile(file) {
    if (modules.has(file.href)) return modules.get(file.href)
    const task = (async () => {
      const source = (await readFile(file, 'utf8')).replaceAll('import.meta.env.DEV', 'false')
      let code = ts.transpileModule(source, { compilerOptions: {
        target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext,
      } }).outputText
      for (const [, specifier] of [...code.matchAll(/(?:from\s*|import\s*)['"](\.[^'"]+)['"]/g)]) {
        const resolved = await compile(new URL(specifier.endsWith('.ts') ? specifier : specifier + '.ts', file))
        code = code.replaceAll("'" + specifier + "'", JSON.stringify(resolved)).replaceAll('"' + specifier + '"', JSON.stringify(resolved))
      }
      return 'data:text/javascript;base64,' + Buffer.from(code + '\n// graph ' + id + ':' + file.href).toString('base64')
    })()
    modules.set(file.href, task)
    return task
  }
  return import(await compile(entry))
}
