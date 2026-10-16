package studio.eidolon.camera

import android.os.Bundle
import android.view.TextureView
import androidx.activity.ComponentActivity
import java.util.concurrent.LinkedBlockingQueue

/** Debug-only surface for exercising Camera2 and writer lifecycle together. */
class CameraHarnessActivity : ComponentActivity() {
    val states = LinkedBlockingQueue<CameraState>()
    lateinit var engine: CameraEngine
    var closeWhenSaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = CameraEngine(applicationContext) { state ->
            if (closeWhenSaving && state.message == "Saving…") {
                closeWhenSaving = false
                engine.stop()
            }
            states.offer(state)
        }
        setContentView(TextureView(this).also(engine::attach))
    }
    override fun onResume() { super.onResume(); engine.start() }
    override fun onPause() { engine.stop(); super.onPause() }
    override fun onDestroy() { engine.destroy(); super.onDestroy() }
}
