export function cleanGeneratedText(text?: string) {
  const lines = (text || '').split(/\r?\n/);
  let fenceMarker: string | undefined;
  const cleaned = lines.filter((line) => {
    const trimmed = line.trim();
    const fence = trimmed.match(/^(`{3,}|~{3,})/);
    if (fence) {
      if (!fenceMarker) {
        fenceMarker = fence[1];
      } else if (fence[1][0] === fenceMarker[0] && fence[1].length >= fenceMarker.length) {
        fenceMarker = undefined;
      }
      return true;
    }
    return fenceMarker || !/^(?:-{3,}|\*{3,}|_{3,})$/.test(trimmed);
  }).join('\n');
  return cleaned
    .replace(/^(?:\s*null\s*)+$/gim, '')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}
