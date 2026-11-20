import { describe, expect, it } from "vitest";
import { imageThumbnailSpec, safeImageFilename } from "./filePreview";

describe("image previews", () => {
  it("recognizes JPEG XL alongside existing safe raster formats", () => {
    for (const file of ["photo.jxl", "PHOTO.JXL", "photo.png", "photo.jpg", "photo.webp"]) {
      expect(safeImageFilename(file)).toBe(true);
    }
  });
  it("does not make active documents or suffix spoofs previewable", () => {
    for (const file of ["photo.svg", "photo.html", "photo.jxl.html", "photo.jxl.exe"]) {
      expect(safeImageFilename(file)).toBe(false);
    }
  });
  it("never requests unsupported JPEG XL thumbnails", () => {
    expect(imageThumbnailSpec("photo.jxl", "100x100")).toBeUndefined();
    expect(imageThumbnailSpec("PHOTO.JXL", "100x100")).toBeUndefined();
    expect(imageThumbnailSpec("photo.png", "100x100")).toBe("100x100");
  });
});
