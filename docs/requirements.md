# Requirements

## Functional Requirements

### Capture
- Capture a single page.
- Capture many pages consecutively.
- Import existing images.
- Preserve capture order.
- Resume an unfinished scanning session.
- Allow deletion and reordering.

### Page Processing
- Manual crop.
- Manual rotation.
- Automatic page boundary detection.
- Perspective correction.
- Basic illumination/contrast correction.
- Preserve diagrams, photographs, charts, and page layout.

### Book-Specific Processing
- Detect a two-page spread where feasible.
- Split a spread into left/right pages.
- Correct page curvature where feasible.
- Reduce gutter shadows where feasible.
- Handle fingers or small occlusions where feasible.

### OCR
- Recognize Japanese text.
- Preserve original page appearance even when OCR fails.
- Allow generation of searchable PDFs using an invisible/overlay text layer.
- Retain OCR confidence/geometry internally when available.

### PDF
- Export all selected pages into one valid PDF.
- Preserve page order.
- Produce a PDF readable by common Android/iOS/desktop PDF readers.
- Later: searchable text layer.
- Later: metadata and bookmarks if useful.

### Engine Selection
- Support Production and From-Scratch implementations.
- Allow benchmarking on identical inputs.
- Later: allow per-stage engine configuration.

## Non-Functional Requirements

### Offline
Core scanning and PDF creation should work without a network connection.

### Privacy
Scanned page data should remain local unless the user explicitly enables an optional network feature.

### Performance
The app must remain usable for book-scale jobs, not only 1–5 page documents.

### Reliability
A long scan session should survive recoverable app interruptions where practical.

### Memory
Do not retain all full-resolution page bitmaps in memory simultaneously.

### Maintainability
Use clear interfaces between major pipeline stages.

### Reproducibility
Important benchmark results should be reproducible.

### OSS Health
Dependencies should be actively maintained or have a strong stability rationale.

### Licensing
Third-party libraries and model weights must be compatible with the repository's license and redistribution model.

## User Experience Requirements

- The capture loop should minimize taps per page.
- The user must see page order clearly.
- Processing should not obscure whether a page is still being processed.
- Manual correction must exist when automatic detection fails.
- A failed OCR step must not destroy the scanned page.
- Book-scale operations should expose progress.

## Out of Scope for Initial MVP

- DRM bypass
- cloud library sync
- social sharing
- commercial scanning workflows
- multi-user collaboration
- reproducing a full modern OCR research stack before the basic scanner works
