package studio.eidolon.camera

/** Retiring a camera reader must not invalidate an image being saved on another thread. */
internal class LeasedResource<T : AutoCloseable>(val value: T) : AutoCloseable {
    private var users = 0
    private var retired = false
    private var closed = false

    @Synchronized fun acquire(): AutoCloseable {
        check(!retired) { "Resource already retired" }
        users++
        var released = false
        return AutoCloseable {
            synchronized(this) {
                if (!released) {
                    released = true
                    users--
                    closeIfUnused()
                }
            }
        }
    }

    @Synchronized override fun close() {
        retired = true
        closeIfUnused()
    }

    private fun closeIfUnused() {
        if (retired && users == 0 && !closed) {
            closed = true
            value.close()
        }
    }
}
