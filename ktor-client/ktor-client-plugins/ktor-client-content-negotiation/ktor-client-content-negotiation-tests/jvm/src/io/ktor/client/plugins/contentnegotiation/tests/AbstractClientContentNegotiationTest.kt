/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package io.ktor.client.plugins.contentnegotiation.tests

import com.fasterxml.jackson.annotation.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlin.test.*

/** Base class for [ContentNegotiation] tests. */
@Suppress("KDocMissingDocumentation")
abstract class AbstractClientContentNegotiationTest : TestWithKtor() {
    private val widget = Widget("Foo", 1000, listOf("a", "b", "c"))
    private val users = listOf(
        User("x", 10),
        User("y", 45)
    )

    override val server: ApplicationEngine = embeddedServer(io.ktor.server.cio.CIO, serverPort) {
        routing {
            createRoutes(this)
        }
    }

    protected abstract val defaultContentType: ContentType
    protected abstract val customContentType: ContentType

    @OptIn(InternalSerializationApi::class)
    private suspend inline fun <reified T : Any> ApplicationCall.respond(
        responseJson: String,
        contentType: ContentType
    ): Unit = respond(responseJson, contentType, T::class.serializer())

    protected open suspend fun <T : Any> ApplicationCall.respond(
        responseJson: String,
        contentType: ContentType,
        serializer: KSerializer<T>,
    ) {
        respondText(responseJson, contentType)
    }

    protected open suspend fun ApplicationCall.respondWithRequestBody(contentType: ContentType) {
        respondText(receiveText(), contentType)
    }

    protected abstract fun ContentNegotiation.Config.configureContentNegotiation(contentType: ContentType)
    protected fun TestClientBuilder<*>.configureClient(
        block: ContentNegotiation.Config.() -> Unit = {}
    ) {
        config {
            install(ContentNegotiation) {
                configureContentNegotiation(defaultContentType)
                block()
            }
        }
    }

    protected open fun createRoutes(routing: Routing): Unit = with(routing) {
        post("/echo") {
            call.respondWithRequestBody(call.request.contentType())
        }
        post("/widget") {
            call.respondWithRequestBody(defaultContentType)
        }
        get("/users") {
            call.respond(
                """{"ok":true,"result":[{"name":"x","age":10},{"name":"y","age":45}]}""",
                defaultContentType,
                Response.serializer(ListSerializer(User.serializer()))
            )
        }
        get("/users-x") { // route for testing custom content type, namely "application/x-${contentSubtype}"
            call.respond(
                """{"ok":true,"result":[{"name":"x","age":10},{"name":"y","age":45}]}""",
                customContentType,
                Response.serializer(ListSerializer(User.serializer()))
            )
        }
        post("/post-x") {
            require(call.request.contentType().withoutParameters() == customContentType) {
                "Request body content type should be $customContentType"
            }

            call.respondWithRequestBody(defaultContentType)
        }
    }

    @Test
    fun testEmptyBody(): Unit = testWithEngine(MockEngine) {
        config {
            engine {
                addHandler { request ->
                    respond(
                        request.body.toByteReadPacket().readText(),
                        headers = headersOf("X-ContentType", request.body.contentType.toString())
                    )
                }
            }
            defaultRequest {
                contentType(defaultContentType)
            }
            install(ContentNegotiation) {
                configureContentNegotiation(defaultContentType)
            }
        }

        test { client ->
            val response: HttpResponse = client.get("https://test.com")
            assertEquals("", response.bodyAsText())
            assertEquals("null", response.headers["X-ContentType"])
        }
    }

    @Test
    fun testSerializeSimple(): Unit = testWithEngine(CIO) {
        configureClient()

        test { client ->
            val result = client.post {
                setBody(widget)
                url(path = "/widget", port = serverPort)
                contentType(defaultContentType)
            }.body<Widget>()

            assertEquals(widget, result)
        }
    }

    @Test
    open fun testSerializeNested(): Unit = testWithEngine(CIO) {
        configureClient()

        test { client ->
            val result = client.get { url(path = "/users", port = serverPort) }.body<Response<List<User>>>()

            assertTrue(result.ok)
            assertNotNull(result.result)
            assertEquals(users, result.result)
        }
    }

    @Test
    fun testCustomContentTypes(): Unit = testWithEngine(CIO) {
        configureClient {
            configureContentNegotiation(customContentType)
        }

        test { client ->
            val result = client.get { url(path = "/users-x", port = serverPort) }.body<Response<List<User>>>()

            assertTrue(result.ok)
            assertNotNull(result.result)
            assertEquals(users, result.result)
        }

        test { client ->
            client.prepareGet { url(path = "/users-x", port = serverPort) }.execute { response ->
                val result = response.body<Response<List<User>>>()

                assertTrue(result.ok)
                assertNotNull(result.result)
                assertEquals(users, result.result)

                assertEquals(customContentType, response.contentType()?.withoutParameters())
            }
        }

        test { client ->
            val payload = User("name1", 99)

            val result = client.post {
                url(path = "/post-x", port = serverPort)
                setBody(payload)
                contentType(customContentType)
            }.body<User>()

            assertEquals(payload, result)
        }
    }

    @Test
    fun testCustomContentTypesMultiple(): Unit = testWithEngine(CIO) {
        configureClient {
            configureContentNegotiation(customContentType)
        }

        test { client ->
            val payload = User("name2", 98)

            val result = client.post {
                url(path = "/post-x", port = serverPort)
                setBody(payload)
                contentType(customContentType)
            }.body<User>()

            assertEquals(payload, result)
        }
    }

    @Test
    fun testCustomContentTypesWildcard(): Unit = testWithEngine(CIO) {
        configureClient {
            configureContentNegotiation(customContentType)
        }

        test { client ->
            client.prepareGet { url(path = "/users-x", port = serverPort) }.execute { response ->
                val result = response.body<Response<List<User>>>()

                assertTrue(result.ok)
                assertNotNull(result.result)
                assertEquals(users, result.result)

                // defaultContentType is registered first on server so it should win
                // since Accept header consist of the wildcard
                assertEquals(defaultContentType, response.contentType()?.withoutParameters())
            }
        }

        test { client ->
            val payload = User("name3", 97)

            val result = client.post {
                url(path = "/post-x", port = serverPort)
                setBody(payload)
                contentType(customContentType) // custom content type should match the wildcard
            }.body<User>()

            assertEquals(payload, result)
        }
    }

    @Test
    open fun testGeneric(): Unit = testWithEngine(CIO) {
        configureClient()

        test { client ->
            val result = client.post {
                url(path = "/echo", port = serverPort)
                contentType(defaultContentType)
                setBody(Response(true, users))
            }.body<Response<List<User>>>()

            assertTrue(result.ok)
            assertNotNull(result.result)
            assertEquals(users, result.result)
        }
    }

    @Test
    open fun testSealed(): Unit = testWithEngine(CIO) {
        configureClient()

        test { client ->
            val result = client.post {
                url(path = "/echo", port = serverPort)
                contentType(defaultContentType)
                setBody(listOf(TestSealed.A("A"), TestSealed.B("B")))
            }.body<List<TestSealed>>()

            assertEquals(listOf(TestSealed.A("A"), TestSealed.B("B")), result)
        }
    }

    @Serializable
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
    sealed class TestSealed {
        @Serializable
        data class A(val valueA: String) : TestSealed()

        @Serializable
        data class B(val valueB: String) : TestSealed()
    }

    @Serializable
    data class Response<T>(
        val ok: Boolean,
        @Contextual
        val result: T?
    )

    @Serializable
    data class Widget(
        val name: String,
        val value: Int,
        val tags: List<String> = emptyList()
    )

    @Serializable
    data class User(
        val name: String,
        val age: Int
    )
}
