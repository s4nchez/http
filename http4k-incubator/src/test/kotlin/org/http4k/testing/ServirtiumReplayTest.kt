package org.http4k.testing

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.http4k.core.Filter
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.util.proxy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Optional

@ExtendWith(ApprovalTest::class)
class ServirtiumReplayTest {

    class Stub(private val t: Any) : ExtensionContext by proxy(), ParameterContext by proxy() {
        override fun getTestInstance() = Optional.of(t)
        override fun getTestMethod() = Optional.of(ServirtiumReplayTest::class.java.getMethod("hashCode"))
    }

    object AContract : ServirtiumContract {
        override val name get() = "name"
    }

    @TempDir
    lateinit var root: File

    @Test
    fun `replays traffic from the recording`(approver: Approver) {
        val manipulations = Filter { next ->
            {
                next(it)
                    .run {
                        header("toBeAdded", "value").body(bodyString().replace("body1", "goodbye"))
                    }
            }
        }

        javaClass.getResourceAsStream("/org/http4k/testing/storedTraffic.txt").use {
            File(root, "name.hashCode.md").writeBytes(it.readAllBytes())
        }

        val stub = Stub(AContract)

        val originalRequest = Request(POST, "/foo")
            .header("header1", "value1")
            .body("body")

        val expectedResponse = Response(OK)
            .header("header3", "value3")
            .header("toBeAdded", "value")
            .body("goodbye")

        val actualResponse = ServirtiumReplay(root, manipulations).resolveParameter(stub, stub)(originalRequest)

        assertThat(actualResponse, equalTo(expectedResponse))
    }
}
