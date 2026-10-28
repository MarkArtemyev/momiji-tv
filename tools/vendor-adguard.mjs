// Regenerates momiji-browser/app/src/main/assets/protection/vendor from the AdGuard npm packages.
//
//   node tools/vendor-adguard.mjs <unpacked @adguard/scriptlets dir> <unpacked @adguard/extended-css dir>
//
// Both packages are GPL-3.0 like Momiji. The scriptlets come from the "corelibs" build, where every
// scriptlet is one standalone function(source, args) with its helpers inlined, so the shield can call
// them without eval and therefore also on pages whose CSP forbids eval.
import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const [scriptletsDir, extendedCssDir] = process.argv.slice(2);
if (!scriptletsDir || !extendedCssDir) {
    console.error('usage: node tools/vendor-adguard.mjs <@adguard/scriptlets package> <@adguard/extended-css package>');
    process.exit(2);
}
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, 'momiji-browser/app/src/main/assets/protection/vendor');
mkdirSync(out, { recursive: true });

const scriptletsPackage = JSON.parse(readFileSync(join(scriptletsDir, 'package.json'), 'utf8'));
const corelibs = JSON.parse(readFileSync(join(scriptletsDir, 'dist/scriptlets.corelibs.json'), 'utf8'));
const functions = [];
const aliases = {};
for (const { names, scriptlet } of corelibs.scriptlets) {
    // Each entry must be a single function expression; anything else would break the whole shield.
    new Function(`return (${scriptlet});`);
    functions.push(`${JSON.stringify(names[0])}:${scriptlet}`);
    for (const alias of names.slice(1)) aliases[alias] = names[0];
}
writeFileSync(join(out, 'scriptlets.js'),
    `/* AdGuard Scriptlets ${scriptletsPackage.version} (corelibs build), GPL-3.0, https://github.com/AdguardTeam/Scriptlets */\n` +
    `var SCRIPTLETS_VERSION=${JSON.stringify(scriptletsPackage.version)};\n` +
    `var SCRIPTLETS={${functions.join(',\n')}};\n` +
    `var SCRIPTLET_ALIASES=${JSON.stringify(aliases)};\n`);

const redirects = JSON.parse(readFileSync(join(scriptletsDir, 'dist/redirects.json'), 'utf8'));
writeFileSync(join(out, 'redirects.json'), JSON.stringify(redirects.map(({ title, aliases, contentType, content }) =>
    ({ title, aliases: aliases || [], contentType, content }))));

const extendedCssPackage = JSON.parse(readFileSync(join(extendedCssDir, 'package.json'), 'utf8'));
const extendedCss = readFileSync(join(extendedCssDir, 'dist/extended-css.min.js'), 'utf8');
new Function(extendedCss);
writeFileSync(join(out, 'extended-css.js'), extendedCss.trim() + '\n');
copyFileSync(join(scriptletsDir, 'LICENSE'), join(out, 'LICENSE-GPL3.txt'));
writeFileSync(join(out, 'VERSIONS.txt'),
    `@adguard/scriptlets ${scriptletsPackage.version}\n@adguard/extended-css ${extendedCssPackage.version}\n`);
console.log(`scriptlets=${functions.length} aliases=${Object.keys(aliases).length} redirects=${redirects.length} ` +
    `extended-css=${extendedCssPackage.version}`);
