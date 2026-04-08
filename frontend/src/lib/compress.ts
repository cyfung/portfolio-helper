// ── compress.ts — Deflate-raw compress/decompress for config codes ────────────

export async function compressToCode(obj: unknown): Promise<string> {
  const bytes = new TextEncoder().encode(JSON.stringify(obj))
  const stream = new CompressionStream('deflate-raw')
  const writer = stream.writable.getWriter()
  writer.write(bytes)
  writer.close()
  const buf = await new Response(stream.readable).arrayBuffer()
  return btoa(String.fromCharCode(...new Uint8Array(buf)))
}

export async function decompressFromCode(code: string): Promise<unknown> {
  const bytes = Uint8Array.from(atob(code), c => c.charCodeAt(0))
  try {
    const stream = new DecompressionStream('deflate-raw')
    const writer = stream.writable.getWriter()
    writer.write(bytes)
    writer.close()
    const buf = await new Response(stream.readable).arrayBuffer()
    return JSON.parse(new TextDecoder().decode(buf))
  } catch {
    // Fallback: old plain base64-JSON codes
    return JSON.parse(new TextDecoder().decode(bytes))
  }
}
