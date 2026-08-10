package dev.bookscanner.core.session

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [bytes] to [target] such that a reader never observes a partially
 * written file: write to a sibling temp file, force it to stable storage,
 * then rename over the target.
 *
 * The rename is the commit point. If the process dies before it, [target]
 * still holds the previous complete content.
 */
internal fun writeAtomically(
    target: File,
    bytes: ByteArray,
) {
    val dir = target.parentFile ?: throw IOException("Target has no parent directory: $target")
    if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
        throw IOException("Cannot create directory: $dir")
    }
    val temp = File.createTempFile(target.name, ".tmp", dir)
    try {
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            // Durability of the temp file's contents must be established
            // before the rename, otherwise a crash can leave a renamed but
            // empty file — worse than no write at all.
            out.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temp.delete()
    }
}
