package org.reekwest.http.core.contract

import org.reekwest.http.core.Request
import org.reekwest.http.core.Response

abstract class MessagePart<in IN, out RAW, out FINAL>(val fn: (IN) -> RAW?) {
    abstract operator fun get(m: IN): FINAL
}

operator fun <T, FINAL> Request.get(param: MessagePart<Request, T, FINAL>): FINAL = param[this]
operator fun <T, FINAL> Response.get(param: MessagePart<Response, T, FINAL>): FINAL = param[this]

open class Optional<in IN, out OUT>(fn: (IN) -> OUT?) : MessagePart<IN, OUT, OUT?>(fn) {
    override operator fun get(m: IN): OUT? = fn(m)
    fun <X> map(next: (OUT) -> X) = Optional<IN, X>({
        try {
            fn(it)?.let(next)
        } catch (e: Exception) {
            throw Invalid(this)
        }
    })
}

open class Required<in IN, out OUT>(fn: (IN) -> OUT?) : MessagePart<IN, OUT, OUT>(fn) {
    override operator fun get(m: IN): OUT = fn(m) ?: throw Missing(this)
    fun <X> map(next: (OUT) -> X) = Required<IN, X>({
        try {
            fn(it)?.let(next)
        } catch (e: Exception) {
            throw Invalid(this)
        }
    })
}

