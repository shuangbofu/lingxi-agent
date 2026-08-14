import { brotliCompressSync, constants } from 'node:zlib';
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { defineConfig } from 'vite';
import type { Plugin } from 'vite';
import react from '@vitejs/plugin-react';

const BROTLI_ASSET_PATTERN = /\.(?:css|html|js|json|svg)$/i;
const BROTLI_MIN_BYTES = 1024;

function brotliAssets(): Plugin {
  let outputDirectory = '';
  return {
    name: 'lingxi-brotli-assets',
    apply: 'build',
    enforce: 'post',
    configResolved(config) {
      outputDirectory = resolve(config.root, config.build.outDir);
    },
    closeBundle() {
      for (const filePath of outputFiles(outputDirectory)) {
        if (!BROTLI_ASSET_PATTERN.test(filePath)) {
          continue;
        }
        const input = readFileSync(filePath);
        if (input.byteLength < BROTLI_MIN_BYTES) {
          continue;
        }
        const compressed = brotliCompressSync(input, {
          params: {
            [constants.BROTLI_PARAM_QUALITY]: 9,
          },
        });
        if (compressed.byteLength >= input.byteLength) {
          continue;
        }
        writeFileSync(`${filePath}.br`, compressed);
      }
    },
  };
}

function outputFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? outputFiles(path) : [path];
  });
}

export default defineConfig({
  plugins: [react(), brotliAssets()],
  build: {
    cssCodeSplit: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
    watch: {
      // Windows 上编辑器/工具以"临时文件 + rename"方式替换文件时，
      // Node fs.watch 对 rename 竞争会抛 EBUSY 并直接崩溃 dev server。
      // 轮询模式不依赖 fs.watch 原生事件，可彻底规避该崩溃。
      usePolling: process.platform === 'win32',
      interval: 300,
      ignored: [/\.tmp$/, /\.tmpdir/],
    },
  },
});
