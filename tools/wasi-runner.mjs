// Chạy file .wasm (wasm32-wasip1) bằng Node. Dùng làm cargo runner trên máy bật
// Smart App Control, nơi Windows chặn mọi .exe chưa ký số do cargo build ra.
import { readFile } from 'node:fs/promises';
import { WASI } from 'node:wasi';
import process from 'node:process';

const [file, ...args] = process.argv.slice(2);
const wasi = new WASI({
  version: 'preview1',
  args: [file, ...args],
  env: process.env,
  preopens: { '.': process.cwd() },
  returnOnExit: true,
});
const module = await WebAssembly.compile(await readFile(file));
const instance = await WebAssembly.instantiate(module, wasi.getImportObject());
process.exitCode = wasi.start(instance);
