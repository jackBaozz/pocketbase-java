/** Previewable raster formats only; active documents such as SVG remain downloads. */
export function safeImageFilename(filename: string): boolean {
  return /\.(?:gif|jpe?g|png|webp|jxl)$/i.test(filename);
}

/** JPEG XL preview uses the original file; the Java thumbnail decoder is PNG-only. */
export function imageThumbnailSpec(filename: string, spec?: string): string | undefined {
  return /\.jxl$/i.test(filename) ? undefined : spec;
}
