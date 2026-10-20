package com.androidmirroring.helper

import android.os.IBinder
import java.lang.reflect.Method

/** Hidden-API access to system services from the shell-uid helper, which has no Context. */
object SystemServices {
    const val SHELL_PACKAGE = "com.android.shell"

    fun get(name: String, aidl: String): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, name) as IBinder
        return Class.forName("$aidl\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /**
     * Calls the only [name] method on [service], filling parameters by type: values in [objects]
     * are matched to the first parameter they fit, ints and strings are taken in order, and
     * anything else is null. AIDL signatures change across Android versions, so matching by
     * position would break on the next release.
     */
    fun call(service: Any, name: String, objects: List<Any> = emptyList(), ints: List<Int> = emptyList(), strings: List<String?> = emptyList()): Any? {
        val method: Method = service.javaClass.methods.first { it.name == name }
        val pendingObjects = objects.toMutableList()
        val pendingInts = ints.iterator()
        val pendingStrings = strings.iterator()
        val args = method.parameterTypes.map { type ->
            when {
                type == Int::class.javaPrimitiveType -> if (pendingInts.hasNext()) pendingInts.next() else 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Boolean::class.javaPrimitiveType -> false
                type == String::class.java -> if (pendingStrings.hasNext()) pendingStrings.next() else null
                else -> pendingObjects.firstOrNull { type.isInstance(it) }?.also { pendingObjects.remove(it) }
            }
        }
        return method.invoke(service, *args.toTypedArray())
    }
}
