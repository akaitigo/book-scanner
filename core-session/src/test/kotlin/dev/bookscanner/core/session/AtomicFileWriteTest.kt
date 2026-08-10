package dev.bookscanner.core.session

import org.junit.After
import org.junit.Before
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomicFileWriteTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir =
            File.createTempFile("atomic-write", "").let {
                it.delete()
                it.mkdirs()
                it
            }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `writes content and creates the file`() {
        val target = File(dir, "manifest.json")
        writeAtomically(target, "hello".toByteArray())
        assertEquals("hello", target.readText())
    }

    @Test
    fun `overwrites existing content`() {
        val target = File(dir, "manifest.json")
        target.writeText("old content that is longer")
        writeAtomically(target, "new".toByteArray())
        assertEquals("new", target.readText())
    }

    @Test
    fun `creates missing parent directories`() {
        val target = File(dir, "a/b/c/manifest.json")
        writeAtomically(target, "nested".toByteArray())
        assertEquals("nested", target.readText())
    }

    @Test
    fun `leaves no temp files behind`() {
        val target = File(dir, "manifest.json")
        repeat(5) { writeAtomically(target, "write-$it".toByteArray()) }
        val leftovers = requireNotNull(dir.listFiles()).map { it.name }.filterNot { it == "manifest.json" }
        assertTrue(leftovers.isEmpty(), "unexpected leftover files: $leftovers")
    }

    /**
     * The point of the rename-commit: a concurrent reader sees either the old
     * bytes or the new bytes, never a truncated file. A non-atomic
     * implementation (open + truncate + write) fails this.
     */
    @Test
    fun `concurrent readers never observe a partial file`() {
        val target = File(dir, "manifest.json")
        val oldContent = "A".repeat(64 * 1024)
        val newContent = "B".repeat(64 * 1024)
        target.writeText(oldContent)

        val stop = AtomicBoolean(false)
        val badRead = AtomicReference<String?>(null)
        val readerReady = CountDownLatch(1)

        val reader =
            Thread {
                readerReady.countDown()
                while (!stop.get()) {
                    val seen = runCatching { target.readText() }.getOrNull() ?: continue
                    if (seen != oldContent && seen != newContent) {
                        badRead.set("length=${seen.length}")
                        return@Thread
                    }
                }
            }
        reader.start()
        assertTrue(readerReady.await(5, TimeUnit.SECONDS))

        repeat(50) {
            writeAtomically(target, newContent.toByteArray())
            writeAtomically(target, oldContent.toByteArray())
        }
        stop.set(true)
        reader.join(TimeUnit.SECONDS.toMillis(5))

        assertNull(badRead.get(), "reader observed a partially written file: ${badRead.get()}")
    }
}
