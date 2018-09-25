package co.touchlab.notepad.utils

import kotlin.system.getTimeMillis
import platform.darwin.*
import kotlin.native.*
import kotlin.native.concurrent.*
import kotlinx.cinterop.*
import co.touchlab.multiplatform.architecture.db.sqlite.*
import co.touchlab.knarch.*

actual fun currentTimeMillis():Long = getTimeMillis()

private var worker :Worker?=null

//Multiple worker contexts get a copy of global state. Not sure about threads created outside of K/N (probably not)
//Lazy create ensures we don't try to create multiple queues
private fun makeQueue():Worker{
    if(worker == null)
    {
        worker = Worker.start()
    }
    return worker!!
}

/**
 * This is 100% absolutely *not* how you should architect background tasks in K/N, but
 * we don't really have a lot of good examples, so here's one that will at least work.
 *
 * Expect everything you pass in to be frozen, and if that's not possible, it'll all fail. Just FYI.
 */
actual fun <B> backgroundTask(backJob:()-> B, mainJob:(B) -> Unit){

    val jobWrapper = JobWrapper(backJob, mainJob).freeze()

    val worker = makeQueue()
    worker.execute(TransferMode.SAFE,
            { jobWrapper }){
        dispatch_async_f(dispatch_get_main_queue(), DetachedObjectGraph {
            ResultAndMain(it.backJob(), it.mainJob).freeze()
        }.asCPointer(), staticCFunction {
            it:COpaquePointer? ->
            initRuntimeIfNeeded()
            val data = DetachedObjectGraph<ResultAndMain<B>>(it).attach()
            data.mainJob(data.result)
        })
    }
}

data class ResultAndMain<B>(val result:B, val mainJob:(B) -> Unit)

/*actual fun <B> backgroundTask(backJob:()-> B, mainJob:(B) -> Unit){

    val jobWrapper = JobWrapper(backJob, mainJob).freeze()

    val worker = makeQueue()
    worker.schedule(TransferMode.CHECKED,
            { jobWrapper }){
        val result  = detachObjectGraph { it.backJob().freeze() as Any }
        dispatch_async(dispatch_get_main_queue()){
            val mainResult = attachObjectGraph<Any>(result) as B
            it.mainJob(mainResult)
        }
    }
}*/

data class JobWrapper<B>(val backJob:()-> B, val mainJob:(B) -> Unit)

actual fun createNativeOpenHelperFactory(): NativeOpenHelperFactory{
    return IosNativeOpenHelperFactory(DefaultSystemContext())
}