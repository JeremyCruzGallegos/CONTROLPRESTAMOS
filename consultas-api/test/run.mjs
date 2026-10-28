import {spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";

const build = spawnSync("python3", [fileURLToPath(new URL("../../tools/preparar_servidor_consultas.py", import.meta.url))], {stdio: "inherit"});
if (build.status !== 0) process.exit(build.status || 1);

for (const name of ["security", "sheets", "registry", "directory", "worker", "runtime"]) {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL(`./${name}.test.mjs`, import.meta.url))], {stdio: "inherit"});
  if (result.status !== 0) process.exit(result.status || 1);
}
