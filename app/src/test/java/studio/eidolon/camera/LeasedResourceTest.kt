package studio.eidolon.camera

import org.junit.Assert.*
import org.junit.Test

class LeasedResourceTest {
    @Test fun cameraShutdownWaitsForAllOutstandingImageSaves() {
        var closes = 0
        val owner = LeasedResource(AutoCloseable { closes++ })
        val first = owner.acquire()
        val second = owner.acquire()
        owner.close()
        assertEquals(0, closes)
        first.close()
        first.close()
        assertEquals(0, closes)
        second.close()
        assertEquals(1, closes)
        owner.close()
        assertEquals(1, closes)
        assertThrows(IllegalStateException::class.java) { owner.acquire() }
    }

    @Test fun completedSaveKeepsActiveCameraReaderOpen() {
        var closes = 0
        val owner = LeasedResource(AutoCloseable { closes++ })
        owner.acquire().close()
        assertEquals(0, closes)
        owner.acquire().close()
        owner.close()
        assertEquals(1, closes)
    }

    @Test fun cameraCanCloseWhileWorkerFinishes() {
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val owner = LeasedResource(AutoCloseable { closes.incrementAndGet() })
        val lease = owner.acquire()
        val worker = Thread { lease.close() }
        worker.start()
        owner.close()
        worker.join()
        assertEquals(1, closes.get())
    }
}
