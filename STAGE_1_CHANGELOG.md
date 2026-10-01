# GITLS 2.43.11 — Modular Batch 2 (File Tools)

## Batch 2
Extracted file-related tools from MainActivity into `FileTools.kt` as extension functions.

### FileTools.kt includes
- File Manager (+ batch copy/move/rename/delete, sort, filter, multi-select)
- Recent files, storage analyzer, file search
- Zip tool
- Large file finder, duplicate finder
- Backup/restore entry, file studio, workspace center
- File convert pipeline (form/progress/done, image BMP/PNG/JPG/WEBP, stream copy)
- Helpers: safeChildFile, safeFileName, scanFiles, shareFileAsync, showFileChecksum, folderSize, etc.

### State kept on MainActivity (internal)
- fileSortMode, fileFilterText
- ConvCategory + convCategories
- convStage / convPickedUri / convToFormat / results, etc.

### Line counts (approx)
- MainActivity: ~15.7k → ~14.4k
- FileTools.kt: ~1.4k lines

### Notes
- openTool() call sites unchanged
- webProjectBuilder restored as minimal stub if damaged during extraction
- Full Gradle build still requires local Android SDK

### Next
- Batch 3: Calculator hub
- Batch 4: Device/system + ESP/IoT
- Repair any compile errors from missing internal helpers after first assembleDebug
